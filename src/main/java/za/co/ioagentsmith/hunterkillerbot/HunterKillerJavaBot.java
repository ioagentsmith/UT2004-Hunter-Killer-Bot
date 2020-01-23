package za.co.ioagentsmith.hunterkillerbot;

import cz.cuni.amis.pogamut.base.utils.guice.AgentScoped;
import cz.cuni.amis.pogamut.ut2004.bot.impl.UT2004Bot;
import cz.cuni.amis.pogamut.ut2004.bot.impl.UT2004BotModuleController;
import cz.cuni.amis.pogamut.ut2004.utils.UT2004BotRunner;
import cz.cuni.amis.utils.exception.PogamutException;

import java.util.logging.Level;

@AgentScoped
public class HunterKillerJavaBot extends UT2004BotModuleController<UT2004Bot> {

    public static final String HUNTER = "HunterBot";

    public void run(Class clazz, int agents) throws PogamutException {
        // starts any number of hunter killer bots specified at once
        // note that this is the most easy way to get a bunch of (the same) bots running at the same time
        new UT2004BotRunner(clazz, HUNTER).setMain(true).setLogLevel(Level.INFO).startAgents(agents);
    }
}
