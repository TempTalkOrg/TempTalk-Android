package com.difft.android.chat.recent





interface ListPaneLayerOpener {
    
    fun openSearchLayer(): Boolean

    
    fun openFoldedGroupsLayer(): Boolean

    




    fun openCreateGroupLayer(preselectedIds: List<String>): Boolean = false

    
    fun showsCreateGroup(): Boolean = false
}
