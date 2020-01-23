package za.co.ioagentsmith.hunterkillerbot

enum class Actions(val move: Int) {

    SPOT_AND_PURSUE(1),
    SHOOTING_AT_NOTHING(2),
    RESPOND_TO_BEING_SHOT_AT(3),
    PURSUE_FLEEING_ENEMY(4),
    DAMAGE_TAKEN(5),
    RUN_AROUND_AND_REARM(6)
}