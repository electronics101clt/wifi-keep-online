package com.zscreen.wifisettings

/**
 * Every signal that means "the unit just came back" across the head-unit families.
 *
 * These radios almost never cold-boot. They *sleep* on ACC-off and wake on ACC-on, many
 * times a day, and on sleep the ROM kills background apps outright -- on this AutoChips
 * unit the system app doing the killing is `com.autochips.quickbootmanager`, which owns
 * `KILL_APPS` and `RESUME_APPS`. So BOOT_COMPLETED alone covers the rarest case and
 * misses the common one.
 *
 * None of this is the primary defence. [Watchdog]'s alarm is vendor-agnostic and fires
 * on wake no matter whose ROM this is, which is what actually makes the app portable.
 * The actions below just get us back faster on the units we can name, and cost nothing
 * on the ones we cannot -- an action that never fires is simply an unused filter.
 */
object Wake {

    /** AutoChips (AC8227L/AC8257) -- read off the target ROM's own package manager. */
    private val AUTOCHIPS = listOf(
        "autochips.intent.action.QB_POWERON",
        "autochips.intent.action.RESUME_APPS",
        "autochips.intent.action.HOME_READY"
    )

    /** MediaTek generic quick-boot, and the HTC-branded variant some ROMs copied. */
    private val MTK = listOf(
        "android.intent.action.QUICKBOOT_POWERON",
        "com.htc.intent.action.QUICKBOOT_POWERON"
    )

    /**
     * Other families, from their own communities rather than from a unit in hand:
     * FYT (`com.fyt.boot.ACCON`) and the PX3/Cayboy lineage (`com.cayboy.action.ACC_ON`).
     * FYT's is reported to require a platform-signed system app, so on those units expect
     * the watchdog to be what actually revives us.
     */
    private val OTHER_VENDORS = listOf(
        "com.fyt.boot.ACCON",
        "com.cayboy.action.ACC_ON"
    )

    val ACTIONS: List<String> = AUTOCHIPS + MTK + OTHER_VENDORS
}
