package com.extbridge.helper.overlay

/**
 * Contract for mutually exclusive external-display popovers (Control Panel, Split Menu,
 * Dock Task Bubble, Window Chrome action menus).
 */
interface OverlayPopover {
    val id: String
    val isOpen: Boolean
    fun dismiss()
}

/**
 * Coordinates mutual exclusion across overlay popovers so opening one automatically
 * dismisses all others without ad-hoc cross-controller callback chains.
 */
class PopoverCoordinator {
    private val popovers = LinkedHashMap<String, OverlayPopover>()

    fun register(popover: OverlayPopover) {
        popovers[popover.id] = popover
    }

    fun unregister(id: String) {
        popovers.remove(id)
    }

    fun isAnyOpen(): Boolean = popovers.values.any { it.isOpen }

    fun dismissAllExcept(keepId: String?) {
        for ((id, popover) in popovers) {
            if (id != keepId && popover.isOpen) {
                popover.dismiss()
            }
        }
    }

    fun dismissAll() {
        dismissAllExcept(null)
    }

    fun clear() {
        popovers.clear()
    }
}
