package org.difft.app.database.models

/** [RoomModel.focusPin]: group focus never touched this room's pin. */
const val ROOM_FOCUS_PIN_NONE = 0

/** [RoomModel.focusPin]: group focus pinned the room automatically; revoked when focus ends or moves. */
const val ROOM_FOCUS_PIN_AUTO = 1

/** [RoomModel.focusPin]: the user changed the pin during focus; permanent, focus leaves it alone. */
const val ROOM_FOCUS_PIN_MANUAL = 2
