package com.onpath.app.plansync

import com.onpath.app.sync.SyncItem
import com.onpath.app.sync.SyncLedger

/**
 * Is a browser at the other end of the phone's pairing (owner 2026-10-31: "why does my phone say connected when the
 * browser doesn't?")? The pairing file alone says only that the phone has a mailbox; a browser that unpaired itself
 * keeps the mailbox and writes its own hello as deleted (web `sync.ts` `unpair`, A-017 point 8 – no DELETE). So the
 * phone reads the browser hellos in its ledger:
 * - [CONNECTED]: at least one live browser hello;
 * - [UNPAIRED]: browser hellos seen, every one deleted – unpaired in the browser (the user's own action: a grey line,
 *   no amber; ☰ offers "Pair with browser" again, a scanned code joins this phone's mailbox, A-017);
 * - [WAITING]: no browser hello yet (just paired, or a browser joining) – shown as paired, as before.
 *
 * A browser hello = a hello of another device that is not a phone (role 2, or none: an older page; a tombstone carries
 * no role). Pure.
 */
enum class BrowserLink {
    CONNECTED, WAITING, UNPAIRED;

    companion object {
        fun of(ledger: SyncLedger, myDevice: String): BrowserLink {
            val hellos = ledger.entries.values.map { it.item }
                .filter { it.kind == SyncItem.DEVICE && it.device != myDevice && (it.deleted || it.role != SyncItem.ROLE_PHONE) }
            return when {
                hellos.any { !it.deleted } -> CONNECTED
                hellos.isNotEmpty() -> UNPAIRED
                else -> WAITING
            }
        }

        /** After this phone sent a browser into its mailbox (JOIN): no "unpaired" until the new browser said hello. */
        fun afterJoin(link: BrowserLink, joined: Boolean): BrowserLink = if (joined && link == UNPAIRED) WAITING else link
    }
}
