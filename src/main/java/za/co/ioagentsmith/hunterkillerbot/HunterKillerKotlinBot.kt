package za.co.ioagentsmith.hunterkillerbot

import cz.cuni.amis.introspection.java.JProp
import cz.cuni.amis.pogamut.base.communication.worldview.listener.annotation.EventListener
import cz.cuni.amis.pogamut.ut2004.agent.module.utils.TabooSet
import cz.cuni.amis.pogamut.ut2004.agent.navigation.NavigationState
import cz.cuni.amis.pogamut.ut2004.agent.navigation.UT2004PathAutoFixer
import cz.cuni.amis.pogamut.ut2004.bot.impl.UT2004Bot
import cz.cuni.amis.pogamut.ut2004.communication.messages.ItemType
import cz.cuni.amis.pogamut.ut2004.communication.messages.UT2004ItemType
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbcommands.Initialize
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbcommands.Rotate
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbcommands.StopShooting
import cz.cuni.amis.pogamut.ut2004.communication.messages.gbinfomessages.*
import cz.cuni.amis.utils.collections.MyCollections
import java.io.File
import java.lang.Exception
import java.util.logging.Level
import java.util.concurrent.TimeUnit

private var instanceCount = 0

class HunterKotlinBot : HunterKillerJavaBot() {
    /**
     * boolean switch to activate engage behavior
     */
    @JProp
    var shouldEngage = true
    /**
     * boolean switch to activate pursue behavior
     */
    @JProp
    var shouldPursue = true
    /**
     * boolean switch to activate rearm behavior
     */
    @JProp
    var shouldRearm = true
    /**
     * boolean switch to activate collect health behavior
     */
    @JProp
    var shouldCollectHealth = true
    /**
     * how low the health level should be to start collecting health items
     */
    @JProp
    var healthLevel = 75
    /**
     * how many bot the hunter killed other bots (i.e., bot has fragged them /
     * got point for killing somebody)
     */
    @JProp
    var frags = 0
    /**
     * how many times the hunter died
     */
    @JProp
    var deaths = 0
    /**
     * Used internally to maintain the information about the bot we're currently
     * hunting, i.e., should be firing at.
     */
    protected var enemy: Player? = null
    /**
     * Item we're running for.
     */
    protected var item: Item? = null
    /**
     * Taboo list of items that are forbidden for some time.
     */
    protected var tabooItems: TabooSet<Item>? = null

    private var autoFixer: UT2004PathAutoFixer? = null

    //////////////////
    // STATE ENGAGE //
    //////////////////
    protected var runningToPlayer = false
    protected var pursueCount = 0

    ////////////////////////////
    // STATE RUN AROUND ITEMS //
    ////////////////////////////
    protected var itemsToRunAround: List<Item>? = null

    ///////////////////////////////////
    // GOAL BASED LEARNING VARIABLES //
    ///////////////////////////////////
    protected var startTime: Long = System.currentTimeMillis()
    protected var endTime: Long = 0L
    protected var numberOfRegressions: Long = 0
    protected var bestFeatureFunction: List<Actions>? = null
    protected var allFeatureFunctions: MutableSet<List<Actions>>? = null
    protected var bestKillsPerSecond: Double = 0.0
    protected var bestKillDeathRatio: Double = 0.0

    protected var totalAllowedNumberOfRegressions: Long = 25
    protected var startingFeatureFunction: List<Actions> =
            mutableListOf(
                    Actions.RUN_AROUND_AND_REARM,
                    Actions.SPOT_AND_PURSUE,
                    Actions.PURSUE_FLEEING_ENEMY,
                    Actions.SHOOTING_AT_NOTHING,
                    Actions.DAMAGE_TAKEN,
                    Actions.RESPOND_TO_BEING_SHOT_AT
            )
    protected var currentFeatureFunction: List<Actions>? = startingFeatureFunction
    protected var numberOfMovesPerFunction: Int = 6
    protected var moveAction = -1

    /**
     * [PlayerKilled] listener that provides "frag" counting + is switches
     * the state of the hunter.
     *
     * @param event
     */
    @EventListener(eventClass = PlayerKilled::class)
    fun playerKilled(event: PlayerKilled) {
        if (event.killer == info.id) {
            ++frags
        }
        if (enemy == null) {
            return
        }
        if (enemy!!.id == event.id) {
            enemy = null
        }
    }

    /**
     * Bot's preparation - called before the bot is connected to GB2004 and
     * launched into UT2004.
     */
    override fun prepareBot(bot: UT2004Bot<*, *, *>?) {
        bot!!.logger.addDefaultFileHandler(File("HunterBot.log"))

        tabooItems = TabooSet(bot)

        autoFixer = UT2004PathAutoFixer(bot, navigation.pathExecutor, fwMap, aStar, navBuilder) // auto-removes wrong navigation links between navpoints

        navigation.state.addListener { changedValue ->
            when (changedValue) {
                NavigationState.PATH_COMPUTATION_FAILED, NavigationState.STUCK -> {
                    if (item != null) {
                        tabooItems!!.add(item, 10.0)
                    }
                    reset()
                }

                NavigationState.TARGET_REACHED -> reset()
            }
        }

        // DEFINE WEAPON PREFERENCES
        weaponPrefs.addGeneralPref(UT2004ItemType.LIGHTNING_GUN, true)
        weaponPrefs.addGeneralPref(UT2004ItemType.SHOCK_RIFLE, true)
        weaponPrefs.addGeneralPref(UT2004ItemType.MINIGUN, false)
        weaponPrefs.addGeneralPref(UT2004ItemType.FLAK_CANNON, true)
        weaponPrefs.addGeneralPref(UT2004ItemType.ROCKET_LAUNCHER, true)
        weaponPrefs.addGeneralPref(UT2004ItemType.LINK_GUN, true)
        weaponPrefs.addGeneralPref(UT2004ItemType.ASSAULT_RIFLE, true)
        weaponPrefs.addGeneralPref(UT2004ItemType.BIO_RIFLE, true)
    }

    /**
     * Here we can modify initializing command for our bot.
     *
     * @return
     */
    override fun getInitializeCommand(): Initialize {
        return Initialize().setName(HUNTER + "-" + ++instanceCount).setDesiredSkill(5)
    }

    /**
     * Handshake with GameBots2004 is over - bot has information about the map
     * in its world view. Many agent modules are usable since this method is
     * called.
     *
     * @param gameInfo      information about the game type
     * @param currentConfig information about configuration
     * @param init          information about configuration
     */
    override fun botInitialized(gameInfo: GameInfo?, currentConfig: ConfigChange?, init: InitedMessage?) {
        bot.logger.getCategory("Parser").level = Level.ALL
    }

    /**
     * The bot is initialized in the environment - a physical representation of
     * the bot is present in the game.
     *
     * @param gameInfo information about the game type
     * @param config   information about configuration
     * @param init     information about configuration
     * @param self     information about the agent
     */
    override fun botFirstSpawn(gameInfo: GameInfo?, config: ConfigChange?, init: InitedMessage?, self: Self?) {
        body.communication.sendGlobalTextMessage("Hello world! I am alive and hunting!")
    }

    /**
     * Simple way to send msg into the UT2004 chat
     */
    private fun sayGlobal(msg: String) {
        body.communication.sendGlobalTextMessage(msg)
        log.info(msg)
    }

    protected fun resetFrags() {
        reset()
        frags = 0
        moveAction = -1
    }

    /**
     * Resets the state of the Hunter.
     */
    protected fun reset() {
        if (currentFeatureFunction == null) {
            currentFeatureFunction = startingFeatureFunction
        } else {
            endTime = System.currentTimeMillis()
            if (allFeatureFunctions == null) {
                allFeatureFunctions = mutableSetOf(startingFeatureFunction)
            }
            allFeatureFunctions!!.add(currentFeatureFunction!!)

            calculateBestRanFeature()
        }

        setNextFeatureToRun()

        item = null
        enemy = null
        navigation.stopNavigation()
        itemsToRunAround = null
        startTime = System.currentTimeMillis()
        endTime = 0L

        sayGlobal("I was KILLED!")
    }

    /**
     * Uses goal based learning, specifically kills per minute and kill death ratio, to evaluate how well the feature function performed
     */
    private fun calculateBestRanFeature() {
        try {
            val seconds: Long = TimeUnit.MILLISECONDS.toSeconds(endTime - startTime)
            val killsPerSecond: Double = (frags / seconds.toInt()).toDouble()
            val killDeathRatio: Double = if (deaths != 0) {
                (frags / deaths).toDouble()
            } else {
                0.0
            }
            val botname = bot.name

            log.info("Bot Name = $botname killsPerSecond = $killsPerSecond killDeathRatio = $killDeathRatio")
            log.info("Bot Name = $botname bestKillsPerSecond = $bestKillsPerSecond bestKillDeathRatio = $bestKillDeathRatio")

            if (killsPerSecond > bestKillsPerSecond) {
                bestKillsPerSecond = killsPerSecond
                bestKillDeathRatio = killDeathRatio
                bestFeatureFunction = currentFeatureFunction
            } else if (killsPerSecond == bestKillsPerSecond && killDeathRatio > bestKillDeathRatio) {
                bestKillsPerSecond = killsPerSecond
                bestKillDeathRatio = killDeathRatio
                bestFeatureFunction = currentFeatureFunction
            }
        } catch (e: Exception) {
            if (bestFeatureFunction == null) {
                bestFeatureFunction = currentFeatureFunction
            }

            log.info(e.message)
        }
    }

    /**
     * Generate the next feature function to be tested and evaluated
     * Please note: At every 5th round, the feature that was the best in the last 4 rounds will run again for added fun
     */
    private fun setNextFeatureToRun() {
        var nextFeatureFunction: MutableList<Actions>
        if (numberOfRegressions % 5 < 1 && bestFeatureFunction != null) {
            nextFeatureFunction = bestFeatureFunction as MutableList<Actions>
        } else {
            nextFeatureFunction = mutableListOf<Actions>()
            for (i in 1..numberOfMovesPerFunction) {
                val randomInteger = (1..numberOfMovesPerFunction).shuffled().first()
                log.info("randomInteger = $randomInteger")
                nextFeatureFunction.add(Actions.values()[randomInteger - 1])
            }
        }

        currentFeatureFunction = nextFeatureFunction
    }

    @EventListener(eventClass = PlayerDamaged::class)
    fun playerDamaged(event: PlayerDamaged) {
        log.info("I have just hurt other bot for: " + event.damageType + "[" + event.damage + "]")
    }

    @EventListener(eventClass = BotDamaged::class)
    fun botDamaged(event: BotDamaged) {
        log.info("I have just been hurt by other bot for: " + event.damageType + "[" + event.damage + "]")
    }

    /**
     * Main method that controls the bot - makes decisions what to do next. It
     * is called iteratively by Pogamut engine every time a synchronous batch
     * from the environment is received. This is usually 4 times per second - it
     * is affected by visionTime variable, that can be adjusted in GameBots ini
     * file in UT2004/System folder.
     *
     * @throws cz.cuni.amis.utils.exception.PogamutException
     */
    override fun logic() {
        var featureFunctionToExcecute: List<Actions>? = null

        if (numberOfRegressions < totalAllowedNumberOfRegressions) {
            featureFunctionToExcecute = currentFeatureFunction
        } else {
            featureFunctionToExcecute = bestFeatureFunction
        }

        if (moveAction < featureFunctionToExcecute!!.size - 1) {
            moveAction++
        } else {
            moveAction = 0
        }

        var action: Actions? = featureFunctionToExcecute.get(moveAction)

        when (action!!.move) {
            1 -> {
                // 1) do you see enemy? 	-> go to PURSUE (start shooting / hunt the enemy)
                if (shouldEngage && players.canSeeEnemies() && weaponry.hasLoadedWeapon()) {
                    stateEngage()
                    return
                }
            }
            2 -> {
                // 2) are you shooting? 	-> stop shooting, you've lost your target
                if (info.isShooting!! || info.isSecondaryShooting!!) {
                    getAct().act(StopShooting())
                }
            }
            3 -> {
                // 3) are you being shot? 	-> go to HIT (turn around - try to find your enemy)
                if (senses.isBeingDamaged) {
                    this.stateHit()
                    return
                }
            }
            4 -> {
                // 4) have you got enemy to pursue? -> go to the last position of enemy
                if (enemy != null && shouldPursue && weaponry.hasLoadedWeapon()) {
                    this.statePursue()
                    return
                }
            }
            5 -> {
                // 5) are you hurt?			-> get yourself some medKit
                if (shouldCollectHealth && info.health < healthLevel) {
                    this.stateMedKit()
                    return
                }
            }
            6 -> {
                // 6) if nothing ... run around for items
                stateRunAroundItems()
                return
            }
        }
    }

    /**
     * Fire when bot sees any enemy.
     * 1.  if enemy that was attacked last time is not visible then choose new enemy
     * 2.  if enemy is reachable and the bot is far - run to him
     * 3.  otherwise - stand still (kind of silly for now)
     *
     */
    protected fun stateEngage() {
        var shooting = false
        var distance = java.lang.Double.MAX_VALUE
        pursueCount = 0

        // 1) pick new enemy if the old one has been lost
        if (enemy == null || !enemy!!.isVisible) {
            // pick new enemy
            enemy = players.getNearestVisiblePlayer(players.visibleEnemies.values)
            if (enemy == null) {
                log.info("Can't see any enemies... ???")
                return
            }
        }

        // 2) stop shooting if enemy is not visible
        if (!enemy!!.isVisible) {
            if (info.isShooting!! || info.isSecondaryShooting!!) {
                // stop shooting
                getAct().act(StopShooting())
            }
            runningToPlayer = false
        } else {
            // 2) or shoot on enemy if it is visible
            distance = info.location.getDistance(enemy!!.location)
            if (shoot.shoot(weaponPrefs, enemy) != null) {
                log.info("Shooting at enemy!!!")
                shooting = true
            }
        }

        // 3) if enemy is far or not visible - run to him
        val decentDistance = Math.round(random.nextFloat() * 800) + 200
        if (!enemy!!.isVisible || !shooting || decentDistance < distance) {
            if (!runningToPlayer) {
                navigation.navigate(enemy)
                runningToPlayer = true
            }
        } else {
            runningToPlayer = false
            navigation.stopNavigation()
        }

        item = null
    }

    ///////////////
    // STATE HIT //
    ///////////////
    protected fun stateHit() {
        //log.info("Decision is: HIT");
        bot.botName.setInfo("HIT")
        if (navigation.isNavigating) {
            navigation.stopNavigation()
            item = null
        }
        getAct().act(Rotate().setAmount(32000))
    }

    //////////////////
    // STATE PURSUE //
    //////////////////
    /**
     * State pursue is for pursuing enemy who was for example lost behind a
     * corner. How it works?:
     * 1.  initialize properties
     * 2.  obtain path to the enemy
     * 3.  follow the path
     *  - if it reaches the end
     *  - set lastEnemy to null
     *  - bot would have seen him before or lost him once for all
     */
    protected fun statePursue() {
        //log.info("Decision is: PURSUE");
        ++pursueCount
        if (pursueCount > 30) {
            reset()
        }
        if (enemy != null) {
            bot.botName.setInfo("PURSUE")
            navigation.navigate(enemy)
            item = null
        } else {
            reset()
        }
    }

    //////////////////
    // STATE MEDKIT //
    //////////////////
    protected fun stateMedKit() {
        //log.info("Decision is: MEDKIT");
        val item = items.getPathNearestSpawnedItem(ItemType.Category.HEALTH)
        if (item == null) {
            log.warning("NO HEALTH ITEM TO RUN TO => ITEMS")
            stateRunAroundItems()
        } else {
            bot.botName.setInfo("MEDKIT")
            navigation.navigate(item)
            this.item = item
        }
    }

    protected fun stateRunAroundItems() {
        //log.info("Decision is: ITEMS");
        //config.setName(HUNTER " [ITEMS]");
        if (navigation.isNavigatingToItem) return

        val interesting = ArrayList<Item>()

        // ADD WEAPONS
        for (itemType in ItemType.Category.WEAPON.types) {
            if (!weaponry.hasLoadedWeapon(itemType)) interesting.addAll(items.getSpawnedItems(itemType).values)
        }
        // ADD ARMORS
        for (itemType in ItemType.Category.ARMOR.types) {
            interesting.addAll(items.getSpawnedItems(itemType).values)
        }
        // ADD QUADS
        interesting.addAll(items.getSpawnedItems(UT2004ItemType.U_DAMAGE_PACK).values)
        // ADD HEALTHS
        if (info.health < 100) {
            interesting.addAll(items.getSpawnedItems(UT2004ItemType.HEALTH_PACK).values)
        }

        val item = MyCollections.getRandom(tabooItems!!.filter(interesting))
        if (item == null) {
            log.warning("NO ITEM TO RUN FOR!")
            if (navigation.isNavigating) return
            bot.botName.setInfo("RANDOM NAV")
            navigation.navigate(navPoints.randomNavPoint)
        } else {
            this.item = item
            log.info("RUNNING FOR: " + item.type.name)
            bot.botName.setInfo("ITEM: " + item.type.name + "")
            navigation.navigate(item)
        }
    }

    ////////////////
    // BOT KILLED //
    ////////////////
    override fun botKilled(event: BotKilled?) {
        deaths++
        numberOfRegressions++
        resetFrags()
    }
}

fun main(args: Array<String>) {
    var agents = 10
    var bot = HunterKotlinBot()

    bot.run(HunterKotlinBot::class.java, agents)
}