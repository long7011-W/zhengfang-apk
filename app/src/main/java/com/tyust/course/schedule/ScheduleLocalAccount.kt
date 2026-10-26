package com.tyust.course.schedule

import com.tyust.course.manager.UserManager

/** Local cached schedules remain useful after server expiry; logout and account removal do not. */
internal fun activeScheduleAccount(user: UserManager = UserManager.getInstance()): String? {
    val session = user.sessionState.state.value
    if (user.currentSchool == null || user.isDemoMode ||
        (!user.isLoggedIn && !(session.expired && user.hasSavedCookie())) ||
        session.token.accountStorageKey != user.currentAccountStorageKey ||
        user.savedAccounts.none { it.key == user.currentAccountKey }) return null
    return user.currentAccountStorageKey
}
