package com.difft.android.base.ui.theme.tokens

import androidx.compose.ui.graphics.Color










object ColorTokens {
    



    const val ScrimDimAmount = 0.5f

    
    const val SheetScrimDimAmountDark = 0.7f

    
    
    val Success = Palette.Green500                        
    val SuccessDark = Palette.Green700                    
    val Warning = Palette.Yellow300                       
    val WarningDark = Palette.Yellow600                   
    
    val Caution = Palette.Orange400                       
    val CautionDark = Palette.Orange600                   
    val Error = Palette.Red400                            
    val ErrorDark = Palette.Red500                        
    val Info = Color(0xFF056FFA)                           
    val InfoLight = Color(0xFFA6F4FA)                      
    
    val BgWhite = Palette.Gray5                           

    val BubbleSent = Color(0xFF474D57)
    val BubbleSentText = Color(0xFFFFFFFF)
    val BubbleSentMeta = Color(0xD9FFFFFF)
    val BubbleSentLink = InfoLight
    val MediaTimeBg = Color(0x6B000000)

    
    object Light {
        val Primary = Palette.Gray800
        val OnPrimary = Palette.Gray0
        val PrimaryPressed = Palette.Gray600             
        val Fill = Color(0x0D000000)
        val Selected = Palette.Gray0
        val AvsysBg = Palette.Gray800
        val AvsysFg = Palette.Gray0

        
        val Background = Palette.Gray0                    
        val Bg = Palette.Gray10                           
        val BackgroundSecondary = Palette.Gray5           
        val BackgroundPopup = Palette.Gray0               
        val BackgroundModal = Palette.Gray0               
        val BackgroundTertiary = Palette.Gray10           
        val BackgroundQuaternary = Palette.Gray50         
        val BackgroundDisabled = Palette.Gray50           
        val BackgroundPagePrimary = Palette.Gray0         
        
        val Bg5 = Palette.Gray5

        val BubbleSentQuote = Color(0x1FFFFFFF)
        val BubbleSentBar = Color(0x66FFFFFF)
        val BubbleRecv = BackgroundTertiary
        val BubbleRecvQuote = Fill

        
        val TextPrimary = Palette.Gray800
        val TextSecondary = Palette.Gray600
        val TextTertiary = Palette.Gray300
        val TextDisabled = Palette.Gray200
        
        val TextOnPrimary = Palette.Gray0

        
        val Line = Palette.Gray50
        val Divider = Palette.Gray50
        val DividerFloating = Palette.Gray50
        val Icon = Palette.Gray800
        val IconOnPrimary = Palette.Gray0

        
        val Ripple = Color(0x1F1E2329)
        
        val Scrim = Color(0x80000000)

        
        
        val Tooltip = Palette.Gray500
    }

    
    object Dark {
        val Primary = Palette.Gray50
        val OnPrimary = Palette.Gray900
        val PrimaryPressed = Palette.Gray200             
        val Fill = Color(0x0FFFFFFF)
        val Selected = Palette.Gray600
        val AvsysBg = Palette.Gray700
        val AvsysFg = Palette.Gray50

        
        val Background = Palette.Gray900                  
        val Bg = Palette.Gray1000                         
        val BackgroundSecondary = Palette.Gray800         
        val BackgroundPopup = Palette.Gray800             
        val BackgroundModal = Palette.Gray700             
        val BackgroundTertiary = Palette.Gray700          
        val BackgroundQuaternary = Palette.Gray600        
        val BackgroundDisabled = Palette.Gray600          
        val BackgroundPagePrimary = Palette.Gray1000      
        val Bg5 = Palette.Gray900                         

        val BubbleSentQuote = Color(0x1AFFFFFF)
        val BubbleSentBar = Color(0x59FFFFFF)
        val BubbleRecv = Background
        val BubbleRecvQuote = Fill

        
        val TextPrimary = Palette.Gray50
        val TextSecondary = Palette.Gray200
        val TextTertiary = Palette.Gray300
        val TextDisabled = Palette.Gray500
        
        val TextOnPrimary = Palette.Gray0

        
        val Line = Palette.Gray600
        val Divider = Palette.Gray700
        
        val DividerFloating = Palette.Gray600
        val Icon = Palette.Gray50
        val IconOnPrimary = Palette.Gray0

        
        val Ripple = Color(0x1FEAECEF)
        
        val Scrim = Color(0x80000000)

        
        
        val Tooltip = Palette.Gray500
    }
}
