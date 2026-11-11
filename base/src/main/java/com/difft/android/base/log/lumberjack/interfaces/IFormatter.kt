package com.difft.android.base.log.lumberjack.interfaces

import com.difft.android.base.log.lumberjack.data.StackData
import timber.log.BaseTree

interface IFormatter {

    





    fun formatLine(tree: BaseTree, prefix: String, message: String): String

    






    fun formatLogPrefix(lumberjackTag: String?, stackData: StackData): String

}