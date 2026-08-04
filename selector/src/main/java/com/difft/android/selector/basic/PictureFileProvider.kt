package com.difft.android.selector.basic

import androidx.core.content.FileProvider

/** Custom FileProvider to avoid FileProvider authority conflicts. */
class PictureFileProvider : FileProvider()
