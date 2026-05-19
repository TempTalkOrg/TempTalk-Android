package com.difft.android.login.intro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme

private const val DESIGN_SIZE = 120f
private const val STROKE_WIDTH_PX = 4f

// ill1.svg path 1 — solid front bubble (-> bg4)
private const val ILL1_SOLID_D =
    "M48.5226 46.0933C58.9199 38.72 73.7706 39.3227 83.3839 47.52C93.1626 55.864 94.4986 69.488 86.4559 79.248" +
            "C78.8799 88.44 65.2159 91.4693 53.8399 86.728L53.2186 86.4587L41.5546 88.9413L41.4479 88.9573L41.3546 88.976" +
            "H41.3066L41.2479 88.9893H41.1466L41.0586 89L41.0026 88.9973L40.9413 89L40.8533 88.992H40.7599L40.7013 88.9813" +
            "L40.6426 88.976L40.5493 88.9573L40.4586 88.944L40.4159 88.9333L40.3519 88.92L40.2213 88.8773L40.1573 88.864" +
            "L40.1279 88.8507L40.0693 88.832L39.9493 88.7787L39.8693 88.7467L39.8399 88.7307L39.8026 88.7147L39.7199 88.6667" +
            "L39.5999 88.6027L39.5573 88.5733L39.4586 88.504L39.3519 88.432L39.3466 88.4213L39.3119 88.3973L39.1973 88.2906" +
            "L39.1306 88.2373L39.1146 88.2187L38.9653 88.0533L38.9306 88.016L38.9013 87.9787L38.7973 87.8293L38.7599 87.7787" +
            "L38.7466 87.752L38.6346 87.5573L38.6159 87.5253L38.6053 87.504L38.5866 87.472L38.5493 87.3707L38.4959 87.2587" +
            "L38.4853 87.216L38.4746 87.1893L38.4293 87.0267L38.4106 86.9787L38.4053 86.9387L38.3919 86.888L38.3786 86.8" +
            "L38.3573 86.688L38.3519 86.6053L38.3439 86.5787V86.536L38.3333 86.392L38.3359 86.296L38.3386 86.2346L38.3439 86.0933" +
            "L38.3546 86.0267V85.976L38.3759 85.8827L38.3893 85.792L38.4026 85.7387L38.4133 85.6853L38.4613 85.5253L38.4693 85.4907" +
            "L41.5359 76.2907L41.4773 76.192C35.5839 66.2 38.2533 53.816 47.9119 46.5413L48.5226 46.0933Z"

// ill1.svg path 2 — dashed back bubble (-> icon, dasharray 4,7)
private const val ILL1_DASHED_D =
    "M38.5226 36.0933C48.9199 28.72 63.7706 29.3227 73.3839 37.52C83.1626 45.864 84.4986 59.488 76.4559 69.248" +
            "C68.8799 78.44 55.2159 81.4693 43.8399 76.728L43.2186 76.4587L31.5546 78.9413L31.4479 78.9573L31.3546 78.976" +
            "H31.3066L31.2479 78.9893H31.1466L31.0586 79L31.0026 78.9973L30.9413 79L30.8533 78.992H30.7599L30.7013 78.9813" +
            "L30.6426 78.976L30.5493 78.9573L30.4586 78.944L30.4159 78.9333L30.3519 78.92L30.2213 78.8773L30.1573 78.864" +
            "L30.1279 78.8507L30.0693 78.832L29.9493 78.7787L29.8693 78.7467L29.8399 78.7307L29.8026 78.7147L29.7199 78.6667" +
            "L29.5999 78.6027L29.5573 78.5733L29.4586 78.504L29.3519 78.432L29.3466 78.4213L29.3119 78.3973L29.1973 78.2906" +
            "L29.1306 78.2373L29.1146 78.2187L28.9653 78.0533L28.9306 78.016L28.9013 77.9787L28.7973 77.8293L28.7599 77.7787" +
            "L28.7466 77.752L28.6346 77.5573L28.6159 77.5253L28.6053 77.504L28.5866 77.472L28.5493 77.3707L28.4959 77.2587" +
            "L28.4853 77.216L28.4746 77.1893L28.4293 77.0267L28.4106 76.9787L28.4053 76.9387L28.3919 76.888L28.3786 76.8" +
            "L28.3573 76.688L28.3519 76.6053L28.3439 76.5787V76.536L28.3333 76.392L28.3359 76.296L28.3386 76.2346L28.3439 76.0933" +
            "L28.3546 76.0267V75.976L28.3759 75.8827L28.3893 75.792L28.4026 75.7387L28.4133 75.6853L28.4613 75.5253L28.4693 75.4907" +
            "L31.5359 66.2907L31.4773 66.192C25.5839 56.2 28.2533 43.816 37.9119 36.5413L38.5226 36.0933Z"

// ill2.svg path 1 — smile bottom arc (-> bg4, solid)
private const val ILL2_SMILE_D =
    "M60 75C70.652 75 80.2271 76.1091 87.0801 77.8662C90.5216 78.7486 93.1644 79.7662 94.8984 80.8174" +
            "C96.7524 81.9413 97 82.7381 97 83C97 83.2619 96.7524 84.0587 94.8984 85.1826C93.1644 86.2338 90.5216 87.2514 87.0801 88.1338" +
            "C80.2271 89.8909 70.652 91 60 91C49.348 91 39.7729 89.8909 32.9199 88.1338C29.4784 87.2514 26.8356 86.2338 25.1016 85.1826" +
            "C23.2476 84.0587 23 83.2619 23 83C23 82.7381 23.2476 81.9413 25.1016 80.8174C26.8356 79.7662 29.4784 78.7486 32.9199 77.8662" +
            "C39.7729 76.1091 49.348 75 60 75Z"

// ill2.svg <ellipse cx=60 cy=55.5 rx=36 ry=8.5> rewritten as 4 cubic bezier segments
// (PathParser does not accept <ellipse>; magic 0.5523 -> control offset = r * 0.5523)
private const val ILL2_ELLIPSE_D =
    "M24,55.5 C24,50.806 40.118,47 60,47 C79.882,47 96,50.806 96,55.5 C96,60.194 79.882,64 60,64 C40.118,64 24,60.194 24,55.5 Z"

// ill2.svg path 3 — lock body (-> icon, solid)
private const val ILL2_LOCK_BODY_D =
    "M41.3333 60.6667C41.3333 59.2522 41.8952 57.8957 42.8953 56.8955C43.8955 55.8953 45.2521 55.3334 46.6666 55.3334" +
            "H73.3333C74.7477 55.3334 76.1043 55.8953 77.1045 56.8955C78.1047 57.8957 78.6666 59.2522 78.6666 60.6667" +
            "V76.6667C78.6666 78.0812 78.1047 79.4377 77.1045 80.4379C76.1043 81.4381 74.7477 82 73.3333 82" +
            "H46.6666C45.2521 82 43.8955 81.4381 42.8953 80.4379C41.8952 79.4377 41.3333 78.0812 41.3333 76.6667V60.6667Z"

// ill2.svg path 4 — lock bow + center dot (-> icon, solid)
private const val ILL2_LOCK_BOW_D =
    "M49.3333 55.3333V44.6667C49.3333 41.8377 50.4571 39.1246 52.4574 37.1242C54.4578 35.1238 57.1709 34 59.9999 34" +
            "C62.8289 34 65.542 35.1238 67.5424 37.1242C69.5428 39.1246 70.6666 41.8377 70.6666 44.6667V55.3333" +
            "M57.3333 68.6667C57.3333 69.3739 57.6142 70.0522 58.1143 70.5523C58.6144 71.0524 59.2927 71.3333 59.9999 71.3333" +
            "C60.7072 71.3333 61.3854 71.0524 61.8855 70.5523C62.3856 70.0522 62.6666 69.3739 62.6666 68.6667" +
            "C62.6666 67.9594 62.3856 67.2811 61.8855 66.781C61.3854 66.281 60.7072 66 59.9999 66" +
            "C59.2927 66 58.6144 66.281 58.1143 66.781C57.6142 67.2811 57.3333 67.9594 57.3333 68.6667Z"

@Composable
internal fun Ill1Messages(modifier: Modifier = Modifier) {
    val solid = DifftTheme.colors.backgroundQuaternary
    val dashed = DifftTheme.colors.icon
    val solidPath = remember { parsePath(ILL1_SOLID_D) }
    val dashedPath = remember { parsePath(ILL1_DASHED_D) }
    Canvas(modifier = modifier.size(120.dp)) {
        val s = size.minDimension / DESIGN_SIZE
        scale(s, s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(
                path = solidPath,
                color = solid,
                style = solidStroke(),
            )
            drawPath(
                path = dashedPath,
                color = dashed,
                style = dashedStroke(floatArrayOf(4f, 7f)),
            )
        }
    }
}

@Composable
internal fun Ill2Lock(modifier: Modifier = Modifier) {
    val accent = DifftTheme.colors.backgroundQuaternary
    val lock = DifftTheme.colors.icon
    val smile = remember { parsePath(ILL2_SMILE_D) }
    val ellipse = remember { parsePath(ILL2_ELLIPSE_D) }
    val lockBody = remember { parsePath(ILL2_LOCK_BODY_D) }
    val lockBow = remember { parsePath(ILL2_LOCK_BOW_D) }
    Canvas(modifier = modifier.size(120.dp)) {
        val s = size.minDimension / DESIGN_SIZE
        scale(s, s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(
                path = smile,
                color = accent,
                style = solidStroke(),
            )
            drawPath(
                path = ellipse,
                color = accent,
                style = dashedStroke(floatArrayOf(4f, 8f)),
            )
            drawPath(
                path = lockBody,
                color = lock,
                style = solidStroke(join = StrokeJoin.Round),
            )
            drawPath(
                path = lockBow,
                color = lock,
                style = solidStroke(join = StrokeJoin.Round),
            )
        }
    }
}

private fun parsePath(d: String): Path = PathParser().parsePathString(d).toPath()

private fun solidStroke(join: StrokeJoin = StrokeJoin.Miter): Stroke = Stroke(
    width = STROKE_WIDTH_PX,
    cap = StrokeCap.Round,
    join = join,
)

private fun dashedStroke(intervals: FloatArray): Stroke = Stroke(
    width = STROKE_WIDTH_PX,
    cap = StrokeCap.Round,
    pathEffect = PathEffect.dashPathEffect(intervals, 0f),
)
