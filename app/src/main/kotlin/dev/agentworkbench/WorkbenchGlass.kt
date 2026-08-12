package dev.agentworkbench

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize

/**
 * "Vidro líquido": os controles flutuam sobre o conteúdo em vez de empurrá-lo, e a hierarquia
 * vem da profundidade, não de mais cor.
 *
 * O Compose não tem `backdrop-filter`. O jeito de conseguir desfoque real do que está atrás é
 * gravar o conteúdo numa [GraphicsLayer] ([backdropSource]) e cada superfície de vidro
 * redesenhar só o pedaço dessa camada que fica atrás dela, com [BlurEffect] aplicado. Grava-se
 * apenas a área da própria superfície, não a tela inteira.
 *
 * Desfoque real exige API 31; abaixo disso sobra o tingimento translúcido, que já carrega a
 * maior parte da leitura de vidro num tema escuro.
 */
private val LocalBackdrop = compositionLocalOf<GraphicsLayer?> { null }

private val blurSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Envolve o conteúdo que as superfícies de vidro vão desfocar. Deve ficar por baixo delas na
 * mesma pilha — tipicamente o conteúdo do Scaffold, com as barras desenhadas por cima.
 */
@Composable
fun BackdropHost(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = rememberGraphicsLayer()
    Box(
        modifier = modifier.drawWithContent {
            backdrop.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop)
        },
    ) {
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            content()
        }
    }
}

/**
 * Superfície de vidro: desfoque do que está atrás + tingimento translúcido + filete especular.
 * O filete é o que faz ler como vidro em vez de simples caixa transparente.
 */
@Composable
fun GlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    tint: Color = WorkbenchTokens.Navigation,
    tintAlpha: Float = 0.72f,
    blurRadius: Float = 28f,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalBackdrop.current
    val blurLayer = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(shape)
            .drawBehind {
                if (backdrop != null && blurSupported && size.width > 0f && size.height > 0f) {
                    blurLayer.renderEffect = BlurEffect(blurRadius, blurRadius, TileMode.Decal)
                    // Grava só a fatia do fundo que cai atrás desta superfície, deslocando a
                    // camada para que a região certa apareça dentro do recorte.
                    blurLayer.record(size = size.toIntSize()) {
                        translate(left = -origin.x, top = -origin.y) {
                            drawLayer(backdrop)
                        }
                    }
                    drawIntoCanvas { drawLayer(blurLayer) }
                }
                // Sem desfoque disponível o tingimento precisa ser mais sólido, senão o texto
                // das barras fica ilegível sobre o conteúdo cru passando por baixo.
                drawRect(
                    color = tint.copy(alpha = if (blurSupported) tintAlpha else 0.94f),
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.05f), Color.Transparent),
                    ),
                )
            }
            .border(1.dp, glassEdgeBrush(), shape),
        content = content,
    )
}

/** Filete especular: mais claro em cima, sumindo embaixo — imita luz batendo na borda. */
fun glassEdgeBrush(): Brush = Brush.verticalGradient(
    listOf(
        Color.White.copy(alpha = 0.14f),
        Color.White.copy(alpha = 0.04f),
        Color.Transparent,
    ),
)
