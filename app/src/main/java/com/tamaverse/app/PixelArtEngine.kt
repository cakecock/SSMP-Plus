package com.tamaverse.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class PetAnimAction {
    NONE, EATING, BATHING, HEALING, PETTING, TICKLING, DANCING, SINGING, PLAYING_TOY, PRAISING, HATCHED
}

enum class RoomSceneType(val title: String, val emoji: String) {
    BEDROOM("Спальня", "🏠"),
    KITCHEN("Кухня", "🍽️"),
    BATHROOM("Ванная", "🛁"),
    BOUTIQUE("Гардероб", "👗"),
    ARCADE("Игровая", "🎮"),
    CITY_SCHOOL("Школа", "🏫"),
    CITY_WORK("Работа", "💼"),
    CITY_PARK("Парк", "🌳"),
    CITY_CINEMA("Кинотеатр", "🎬"),
    CITY_TRAVEL("Путешествие", "✈️")
}

private fun Color.darken(factor: Float = 0.76f): Color =
    Color((red * factor).coerceIn(0f, 1f), (green * factor).coerceIn(0f, 1f), (blue * factor).coerceIn(0f, 1f), alpha)

private fun Color.lighten(factor: Float = 0.30f): Color =
    Color((red + (1f - red) * factor).coerceIn(0f, 1f), (green + (1f - green) * factor).coerceIn(0f, 1f), (blue + (1f - blue) * factor).coerceIn(0f, 1f), alpha)

private fun DrawScope.px(
    x: Int,
    y: Int,
    w: Int = 1,
    h: Int = 1,
    color: Color,
    cell: Float,
    ox: Float,
    oy: Float
) {
    drawRect(
        color = color,
        topLeft = Offset(ox + x * cell, oy + y * cell),
        size = Size(w * cell + 0.65f, h * cell + 0.65f)
    )
}

private fun DrawScope.outlinedBox(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    fill: Color,
    outline: Color,
    cell: Float,
    ox: Float,
    oy: Float
) {
    px(x, y, w, h, outline, cell, ox, oy)
    if (w > 2 && h > 2) {
        px(x + 1, y + 1, w - 2, h - 2, fill, cell, ox, oy)
    }
}

/**
 * 2D пиксельный буфер 64×64 с автоматической 1-пиксельной внешней обводкой!
 * Даёт плавные изогнутые формы без "квадратности" — как на скриншотах Touma Pet.
 */
private class PixelBuffer64 {
    val grid = IntArray(64 * 64) { 0 }

    fun set(x: Int, y: Int, color: Color) {
        if (x in 0..63 && y in 0..63) {
            grid[y * 64 + x] = color.toArgb()
        }
    }

    fun get(x: Int, y: Int): Int {
        if (x !in 0..63 || y !in 0..63) return 0
        return grid[y * 64 + x]
    }

    fun fillEllipse(cx: Float, cy: Float, rx: Float, ry: Float, color: Color) {
        val x0 = (cx - rx - 1).toInt().coerceAtLeast(1)
        val x1 = (cx + rx + 1).toInt().coerceAtMost(62)
        val y0 = (cy - ry - 1).toInt().coerceAtLeast(1)
        val y1 = (cy + ry + 1).toInt().coerceAtMost(62)
        val argb = color.toArgb()
        for (y in y0..y1) {
            val dy = (y - cy) / ry
            for (x in x0..x1) {
                val dx = (x - cx) / rx
                if (dx * dx + dy * dy <= 1.0f) {
                    grid[y * 64 + x] = argb
                }
            }
        }
    }

    fun fillRoundedRect(x: Int, y: Int, w: Int, h: Int, r: Int, color: Color) {
        val argb = color.toArgb()
        for (py in y until (y + h)) {
            for (px in x until (x + w)) {
                if (px !in 1..62 || py !in 1..62) continue
                val inLeft = px < x + r
                val inRight = px >= x + w - r
                val inTop = py < y + r
                val inBot = py >= y + h - r
                var draw = true
                if (inLeft && inTop) {
                    val dx = (x + r - 0.5f) - px; val dy = (y + r - 0.5f) - py
                    if (dx * dx + dy * dy > r * r) draw = false
                } else if (inRight && inTop) {
                    val dx = px - (x + w - r - 0.5f); val dy = (y + r - 0.5f) - py
                    if (dx * dx + dy * dy > r * r) draw = false
                } else if (inLeft && inBot) {
                    val dx = (x + r - 0.5f) - px; val dy = py - (y + h - r - 0.5f)
                    if (dx * dx + dy * dy > r * r) draw = false
                } else if (inRight && inBot) {
                    val dx = px - (x + w - r - 0.5f); val dy = py - (y + h - r - 0.5f)
                    if (dx * dx + dy * dy > r * r) draw = false
                }
                if (draw) grid[py * 64 + px] = argb
            }
        }
    }

    fun fillStarHead(cx: Float, cy: Float, outerR: Float, innerR: Float, points: Int, color: Color) {
        val argb = color.toArgb()
        val rInt = outerR.toInt() + 1
        for (y in (cy.toInt() - rInt)..(cy.toInt() + rInt)) {
            for (x in (cx.toInt() - rInt)..(cx.toInt() + rInt)) {
                if (x !in 1..62 || y !in 1..62) continue
                val dx = x - cx
                val dy = y - cy
                val dist = sqrt(dx * dx + dy * dy)
                var angle = kotlin.math.atan2(dy.toDouble(), dx.toDouble()) + PI / 2.0
                if (angle < 0) angle += 2.0 * PI
                val sector = (2.0 * PI) / points
                val local = (angle % sector) / sector
                val tri = 1.0 - abs(local * 2.0 - 1.0)
                val maxR = innerR + (outerR - innerR) * tri.toFloat()
                if (dist <= maxR) grid[y * 64 + x] = argb
            }
        }
    }

    fun fillCapsule(x1: Float, y1: Float, x2: Float, y2: Float, radius: Float, color: Color) {
        val steps = maxOf(abs(x2 - x1), abs(y2 - y1)).toInt().coerceAtLeast(1) * 2
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            fillEllipse(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, radius, radius, color)
        }
    }

    fun applyShadeOnFilled(shadeColor: Color, highlightColor: Color, cyCenter: Int) {
        val shadeArgb = shadeColor.toArgb()
        val hiArgb = highlightColor.toArgb()
        val copy = grid.clone()
        for (y in 2..61) {
            for (x in 2..61) {
                val c = copy[y * 64 + x]
                if (c == 0) continue
                // Верхний блик (если 2px выше пусто)
                if (y < cyCenter && copy[(y - 2) * 64 + x] == 0 && copy[(y - 1) * 64 + x] != 0) {
                    grid[y * 64 + x] = hiArgb
                }
                // Нижняя мягкая тень (если 1-2px ниже пусто)
                if (y > cyCenter && copy[(y + 2) * 64 + x] == 0 && copy[(y + 1) * 64 + x] != 0) {
                    grid[y * 64 + x] = shadeArgb
                }
            }
        }
    }

    fun renderWithAutoOutline(scope: DrawScope, outline: Color, cell: Float, ox: Float, oy: Float) {
        // 1-й проход: автоматический плавный 1px контур вокруг всех непустых пикселей!
        for (y in 0..63) {
            for (x in 0..63) {
                if (get(x, y) != 0) continue
                val touches = get(x - 1, y) != 0 || get(x + 1, y) != 0 || get(x, y - 1) != 0 || get(x, y + 1) != 0
                if (touches) {
                    scope.px(x, y, 1, 1, outline, cell, ox, oy)
                }
            }
        }
        // 2-й проход: заливка пикселей (горизонтальные пробеги для производительности!)
        for (y in 0..63) {
            var x = 0
            while (x < 64) {
                val argb = grid[y * 64 + x]
                if (argb == 0) {
                    x++
                    continue
                }
                var run = 1
                while (x + run < 64 && grid[y * 64 + x + run] == argb) run++
                scope.px(x, y, run, 1, Color(argb), cell, ox, oy)
                x += run
            }
        }
    }
}

@Composable
fun PixelPetAvatar(
    pet: PetDefinition,
    stage: PetStage,
    palette: PetPalette = pet.defaultPalette,
    modifier: Modifier = Modifier,
    frame: Int = 0,
    isSleeping: Boolean = false,
    isSick: Boolean = false,
    isHappy: Boolean = false,
    eggTaps: Int = 0,
    hatId: String? = null,
    accId: String? = null,
    equipped: EquippedWardrobe? = null,
    mood: PetMood = PetMood.HAPPY,
    isDeceased: Boolean = false,
    isSilhouette: Boolean = false
) {
    val resolvedEquipped = equipped ?: EquippedWardrobe(
        head = hatId,
        face = accId?.takeIf { it.contains("shades") || it.contains("glasses") },
        back = accId?.takeIf { it.contains("cape") }
    )
    val effectiveMood = when {
        isDeceased -> PetMood.DECEASED
        isSick -> PetMood.SICK
        isSleeping -> PetMood.SLEEPY
        isHappy -> PetMood.ECSTATIC
        else -> mood
    }
    Canvas(modifier = modifier) {
        val grid = 64
        val cell = min(size.width, size.height) / grid
        val ox = (size.width - cell * grid) / 2f
        val oy = (size.height - cell * grid) / 2f
        drawCurvedPet64(
            pet = pet,
            palette = palette,
            stage = stage,
            frame = frame,
            mood = effectiveMood,
            isSleeping = isSleeping,
            isSick = isSick,
            isDeceased = isDeceased,
            eggTaps = eggTaps,
            equipped = if (isSilhouette) EquippedWardrobe() else resolvedEquipped,
            isSilhouette = isSilhouette,
            cell = cell,
            ox = ox,
            oy = oy
        )
    }
}

@Composable
fun PixelRoomStage(
    account: AccountSaveState,
    state: PetSaveState,
    pet: PetDefinition,
    scene: RoomSceneType,
    frame: Int,
    action: PetAnimAction,
    speechBubble: String?,
    lastFedFoodId: String = "onigiri",
    activeTravelId: String = "japan",
    weather: WeatherType = WeatherType.SUNNY,
    bonusButterflyActive: Boolean = false,
    onBonusTap: () -> Unit = {},
    onRoomTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val theme = remember(account.roomThemeId) { RoomThemeCatalog.getById(account.roomThemeId) }
    val palette = remember(state.paletteName, pet.id) { state.safePalette(pet) }
    val decorId = account.roomDecorId
    val interaction = remember { MutableInteractionSource() }
    val mood = state.currentMood(action)

    Box(
        modifier = modifier
            .clickable(interactionSource = interaction, indication = null) { onRoomTap() }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cols = 96
            val cell = size.width / cols
            val rows = ceil(size.height / cell).toInt().coerceAtLeast(88)
            val ox = 0f
            val oy = 0f
            val floorTop = (rows * 0.62f).toInt().coerceAtLeast(52)

            when (scene) {
                RoomSceneType.BEDROOM -> drawBedroomScene(cols, rows, floorTop, theme, decorId, hour, weather, state, frame, cell, ox, oy)
                RoomSceneType.KITCHEN -> drawKitchenBackScene(cols, rows, floorTop, theme, cell, ox, oy)
                RoomSceneType.BATHROOM -> drawBathroomScene(cols, rows, floorTop, frame, cell, ox, oy)
                RoomSceneType.BOUTIQUE -> drawBoutiqueScene(cols, rows, floorTop, frame, cell, ox, oy)
                RoomSceneType.ARCADE -> drawArcadeScene(cols, rows, floorTop, frame, cell, ox, oy)
                RoomSceneType.CITY_SCHOOL -> drawSchoolScene(cols, rows, floorTop, frame, cell, ox, oy)
                RoomSceneType.CITY_WORK -> drawWorkScene(cols, rows, floorTop, frame, cell, ox, oy)
                RoomSceneType.CITY_PARK -> drawParkScene(cols, rows, floorTop, frame, cell, ox, oy)
                RoomSceneType.CITY_CINEMA -> drawCinemaScene(cols, rows, floorTop, frame, cell, ox, oy)
                RoomSceneType.CITY_TRAVEL -> drawTravelScene(cols, rows, floorTop, activeTravelId, frame, cell, ox, oy)
            }

            val roamX = when {
                state.isSleeping && scene == RoomSceneType.BEDROOM -> 65
                action == PetAnimAction.DANCING -> 16 + ((sin(frame * 0.65) * 6).toInt())
                scene == RoomSceneType.BEDROOM -> 16 + ((sin(frame * 0.18) * 5).toInt())
                else -> 16
            }
            val petSizeCells = when (state.stage()) {
                PetStage.EGG -> 36
                PetStage.BABY -> 46
                PetStage.CHILD -> 52
                PetStage.TEEN -> 58
                PetStage.ADULT -> 64
            }
            val pCell = cell * (petSizeCells / 64f)

            if (scene == RoomSceneType.BEDROOM && state.isSleeping && state.stage() != PetStage.EGG && !state.isDeceased) {
                val bedPetX = 63f * cell
                val bedPetY = (floorTop - 13) * cell
                drawCurvedPet64(
                    pet = pet, palette = palette, stage = state.stage(), frame = frame,
                    mood = PetMood.SLEEPY, isSleeping = true, isSick = state.isSick, isDeceased = false,
                    eggTaps = state.eggTaps, equipped = state.equipped(), isSilhouette = false,
                    cell = cell * 0.44f, ox = bedPetX, oy = bedPetY
                )
                val blanket = Color(theme.bedBlanket)
                outlinedBox(62, floorTop + 1, 28, 12, blanket, Color(0xFF281938), cell, ox, oy)
                px(64, floorTop + 3, 24, 2, blanket.lighten(0.3f), cell, ox, oy)
            } else {
                // На кухне питомец стоит прямо за столом (чуть выше стола)!
                val kitchenShift = if (scene == RoomSceneType.KITCHEN) -5 * cell else 0f
                val pOx = ox + roamX * cell + (64 * cell - 64 * pCell) / 2f
                val pOy = oy + (floorTop + 2) * cell - 46 * pCell + kitchenShift +
                    (if (state.isDeceased) sin(frame * 0.4).toFloat() * 3f * cell else 0f)

                if (scene != RoomSceneType.KITCHEN) {
                    drawOval(
                        color = Color(0x361B0D2B),
                        topLeft = Offset(ox + (roamX + 14) * cell, oy + (floorTop + 13) * cell),
                        size = Size(36 * cell, 7 * cell)
                    )
                }

                if (scene == RoomSceneType.BATHROOM && state.stage() != PetStage.EGG) {
                    outlinedBox(20, floorTop - 4, 56, 16, Color(0xFFD6F5FF), Color(0xFF2A3B5C), cell, ox, oy)
                    px(22, floorTop - 2, 52, 6, Color(0xFF7AD7F0), cell, ox, oy)
                }

                drawCurvedPet64(
                    pet = pet,
                    palette = palette,
                    stage = state.stage(),
                    frame = frame,
                    mood = mood,
                    isSleeping = state.isSleeping,
                    isSick = state.isSick,
                    isDeceased = state.isDeceased,
                    eggTaps = state.eggTaps,
                    equipped = state.equipped(),
                    isSilhouette = false,
                    cell = pCell,
                    ox = pOx,
                    oy = pOy
                )

                // Стол и тарелка С ЕДОЙ рисуются СПЕРЕДИ питомца на кухне!
                if (scene == RoomSceneType.KITCHEN) {
                    drawKitchenForegroundTable(floorTop, lastFedFoodId, action, frame, cell, ox, oy)
                }

                if (scene == RoomSceneType.BATHROOM && state.stage() != PetStage.EGG) {
                    outlinedBox(18, floorTop + 3, 60, 14, Color.White, Color(0xFF2A3B5C), cell, ox, oy)
                    px(20, floorTop + 5, 56, 4, Color(0xFFADE8F4), cell, ox, oy)
                    for (bx in 20..70 step 7) {
                        val lift = if ((bx + frame) % 2 == 0) 0 else -1
                        outlinedBox(bx, floorTop + lift, 8, 5, Color.White, Color(0xFF7AD7F0), cell, ox, oy)
                    }
                    outlinedBox(65, floorTop - 3, 7, 5, Color(0xFFFFD166), Color(0xFF3D2817), cell, ox, oy)
                    px(71, floorTop - 1, 2, 2, Color(0xFFFF7F11), cell, ox, oy)
                }
            }

            if (scene == RoomSceneType.BEDROOM) {
                for (p in 0 until state.poopCount.coerceAtMost(3)) {
                    val pxPos = 10 + p * 10
                    val pyPos = floorTop + 14
                    outlinedBox(pxPos + 1, pyPos - 4, 4, 3, Color(0xFF8D5524), Color(0xFF2B1608), cell, ox, oy)
                    outlinedBox(pxPos, pyPos - 1, 6, 4, Color(0xFF704214), Color(0xFF2B1608), cell, ox, oy)
                    if (frame % 2 == 0) px(pxPos + 2, pyPos - 7, 1, 2, Color(0xFFB5E48C), cell, ox, oy)
                }
            }

            drawSceneActionEffects(action, state.isDeceased, floorTop, roamX, frame, cell, ox, oy)

            // ФИКС СНА: ночное затемнение накладываем ПОСЛЕ кровати, одеяла и питомца!
            if (scene == RoomSceneType.BEDROOM && state.isSleeping) {
                drawRect(Color(0x6B090D24), Offset.Zero, size)
                drawCircle(
                    color = Color(0x18FFE599),
                    radius = 18 * cell,
                    center = Offset(74 * cell, 25 * cell)
                )
            }
        }

        // Летающий бонус — золотая бабочка в спальне!
        if (bonusButterflyActive && scene == RoomSceneType.BEDROOM && !state.isSleeping && !state.isDeceased) {
            val flap = if (frame % 2 == 0) "🦋✨" else "✨🦋"
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xEEFFD166),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = (54 + (frame % 6) * 4).dp, start = (22 + ((frame % 8) * 14)).dp)
                    .clickable { onBonusTap() }
            ) {
                Text(
                    text = "$flap +15🪙",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color(0xFF281938),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp
                )
            }
        }

        if (!speechBubble.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp, start = 20.dp, end = 20.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xF5FFFFFF),
                shadowElevation = 6.dp
            ) {
                Text(
                    text = speechBubble,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    color = Color(0xFF26173D),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// =====================================================================
// СЦЕНЫ КОМНАТ И ГОРОДСКИХ ЛОКАЦИЙ (96 КОЛОНОК)
// =====================================================================

private fun DrawScope.drawBedroomScene(
    cols: Int, rows: Int, floorTop: Int,
    theme: RoomTheme, decorId: String, hour: Int,
    weather: WeatherType,
    state: PetSaveState, frame: Int,
    cell: Float, ox: Float, oy: Float
) {
    val outline = Color(0xFF2E1E3E)
    val wallMain = Color(theme.wallMain)
    val wallStripe = Color(theme.wallStripe)
    val wainscot = Color(theme.wainscot)
    val floorA = Color(theme.floorA)
    val floorB = Color(theme.floorB)
    val rugOuter = Color(theme.rugOuter)
    val rugInner = Color(theme.rugInner)
    val curtainCol = Color(theme.curtain)
    val bedBlanket = Color(theme.bedBlanket)

    px(0, 0, cols, 6, wainscot.darken(0.75f), cell, ox, oy)
    px(0, 5, cols, 2, outline, cell, ox, oy)
    px(0, 2, cols, 1, wainscot.darken(0.55f), cell, ox, oy)

    for (x in 0 until cols step 4) {
        px(x, 7, 4, floorTop - 7, if ((x / 4) % 2 == 0) wallMain else wallStripe, cell, ox, oy)
    }
    px(0, floorTop - 6, cols, 6, wainscot, cell, ox, oy)
    px(0, floorTop - 7, cols, 1, outline, cell, ox, oy)
    px(0, floorTop, cols, 1, outline, cell, ox, oy)

    px(47, 5, 2, 5, outline, cell, ox, oy)
    outlinedBox(42, 10, 12, 4, Color(0xFFFF6B6B), outline, cell, ox, oy)
    px(45, 14, 6, 2, Color(0xFFFFF59D), cell, ox, oy)

    outlinedBox(43, 18, 10, 10, Color(0xFFFFD166), outline, cell, ox, oy)
    outlinedBox(42, 16, 4, 4, Color(0xFFFFB703), outline, cell, ox, oy)
    outlinedBox(50, 16, 4, 4, Color(0xFFFFB703), outline, cell, ox, oy)
    px(45, 20, 6, 6, Color.White, cell, ox, oy)
    px(47, 21, 1, 3, outline, cell, ox, oy)
    px(47, 23, 3, 1, Color(0xFFE63946), cell, ox, oy)

    outlinedBox(28, 20, 10, 12, Color(0xFFC77D32), outline, cell, ox, oy)
    px(30, 22, 6, 8, Color(0xFF8ECAE6), cell, ox, oy)
    px(31, 25, 4, 4, Color(0xFF52B788), cell, ox, oy)

    val isNight = hour >= 20 || hour < 6 || weather == WeatherType.STARFALL
    val isEvening = hour in 17..19
    val skyColor = when {
        weather == WeatherType.STORM -> if (frame % 18 == 0) Color(0xFFBDE0FE) else Color(0xFF1D2440)
        weather == WeatherType.RAIN -> Color(0xFF4A6FA5)
        weather == WeatherType.CLOUDY -> Color(0xFF8FB8DE)
        weather == WeatherType.SNOW -> Color(0xFF9AC5F4)
        state.isSleeping || isNight -> Color(0xFF141E46)
        isEvening -> Color(0xFFFF8866)
        else -> Color(0xFF72DDF7)
    }
    outlinedBox(62, 15, 24, 22, wainscot.darken(0.8f), outline, cell, ox, oy)
    px(64, 17, 20, 18, skyColor, cell, ox, oy)
    when (weather) {
        WeatherType.RAIN, WeatherType.STORM -> {
            px(66, 18, 16, 4, Color(0xFF6C7A9C), cell, ox, oy)
            for (d in 0..4) {
                val rx = 66 + d * 4
                val ry = 23 + ((frame * 2 + d * 3) % 10)
                px(rx, ry, 1, 3, Color(0xFFA8E6FF), cell, ox, oy)
            }
            if (weather == WeatherType.STORM && frame % 18 in 0..1) {
                px(76, 20, 2, 6, Color(0xFFFFF3B0), cell, ox, oy)
                px(75, 25, 2, 5, Color(0xFFFFF3B0), cell, ox, oy)
            }
        }
        WeatherType.SNOW -> {
            px(64, 33, 20, 2, Color.White, cell, ox, oy)
            for (s in 0..5) {
                val sx = 65 + s * 3 + (if (frame % 2 == 0) 0 else 1)
                val sy = 18 + ((frame + s * 3) % 14)
                px(sx, sy, 2, 2, Color.White, cell, ox, oy)
            }
        }
        WeatherType.STARFALL -> {
            px(67, 20, 3, 3, Color(0xFFFFF3B0), cell, ox, oy)
            val sx = 66 + (frame % 12)
            val sy = 19 + (frame % 10)
            px(sx, sy, 4, 2, Color(0xFFFFE066), cell, ox, oy)
            if (frame % 4 < 2) px(80, 22, 2, 2, Color.White, cell, ox, oy)
        }
        WeatherType.CLOUDY -> {
            px(66, 20, 9, 4, Color.White, cell, ox, oy)
            px(74, 24, 8, 4, Color(0xFFE2E8F0), cell, ox, oy)
        }
        WeatherType.SUNNY -> {
            if (state.isSleeping || isNight) {
                px(68, 20, 3, 3, Color(0xFFFFF3B0), cell, ox, oy)
                if (frame % 4 < 2) px(78, 22, 2, 2, Color.White, cell, ox, oy)
            } else {
                px(67, 20, 4, 4, Color(0xFFFFE066), cell, ox, oy)
                px(74, 22, 7, 3, Color.White, cell, ox, oy)
            }
        }
    }
    px(73, 17, 2, 18, wainscot.darken(0.8f), cell, ox, oy)
    px(64, 25, 20, 2, wainscot.darken(0.8f), cell, ox, oy)
    outlinedBox(60, 14, 5, 24, curtainCol, outline, cell, ox, oy)
    outlinedBox(83, 14, 5, 24, curtainCol, outline, cell, ox, oy)

    val tileH = 5
    val tileW = 8
    for (y in (floorTop + 1) until rows step tileH) {
        val rowIdx = (y - floorTop) / tileH
        for (x in 0 until cols step tileW) {
            val colIdx = x / tileW
            px(x, y, tileW, tileH, if ((rowIdx + colIdx) % 2 == 0) floorA else floorB, cell, ox, oy)
        }
    }

    val rugTop = floorTop + 8
    val rugH = (rows - rugTop - 6).coerceAtLeast(14)
    outlinedBox(16, rugTop, 64, rugH, rugOuter, outline, cell, ox, oy)
    px(19, rugTop + 2, 58, rugH - 4, rugInner, cell, ox, oy)

    outlinedBox(4, floorTop - 10, 18, 18, Color(0xFFFFB76B), outline, cell, ox, oy)
    for (dy in 0..2) {
        outlinedBox(6, floorTop - 8 + dy * 5, 14, 4, Color(0xFFFFD6A5), outline, cell, ox, oy)
        px(12, floorTop - 7 + dy * 5, 2, 2, Color(0xFFFF6B9D), cell, ox, oy)
    }
    when (decorId) {
        "aquarium" -> {
            outlinedBox(5, floorTop - 22, 16, 12, Color(0xFF48CAE4), outline, cell, ox, oy)
            val fx = 7 + (frame % 8)
            px(fx, floorTop - 17, 4, 2, Color(0xFFFF9F1C), cell, ox, oy)
            px(fx + 3, floorTop - 16, 1, 1, Color.White, cell, ox, oy)
        }
        "gaming_pc" -> {
            outlinedBox(5, floorTop - 21, 15, 11, Color(0xFF1A1B2E), outline, cell, ox, oy)
            val rgb = listOf(Color(0xFF00E5FF), Color(0xFFFF2E93), Color(0xFF7BE495))[frame % 3]
            px(7, floorTop - 19, 11, 7, rgb.copy(alpha = 0.7f), cell, ox, oy)
        }
        "fireplace" -> {
            outlinedBox(3, floorTop - 16, 20, 24, Color(0xFF8D5B4C), outline, cell, ox, oy)
            px(7, floorTop - 6, 12, 12, Color(0xFF2B1D1A), cell, ox, oy)
            val fh = if (frame % 2 == 0) 6 else 8
            px(9, floorTop + 5 - fh, 8, fh, Color(0xFFFF7F11), cell, ox, oy)
            px(11, floorTop + 5 - fh / 2, 4, fh / 2, Color(0xFFFFE066), cell, ox, oy)
        }
        "bookshelf" -> {
            outlinedBox(4, floorTop - 26, 16, 32, Color(0xFF8B5E3C), outline, cell, ox, oy)
            px(6, floorTop - 22, 4, 6, Color(0xFFE63946), cell, ox, oy)
            px(11, floorTop - 21, 3, 5, Color(0xFF457B9D), cell, ox, oy)
            px(15, floorTop - 22, 3, 6, Color(0xFF2A9D8F), cell, ox, oy)
            px(6, floorTop - 12, 5, 6, Color(0xFFFFB703), cell, ox, oy)
            px(12, floorTop - 11, 5, 5, Color(0xFF9B5DE5), cell, ox, oy)
        }
        "teddy" -> {
            outlinedBox(6, floorTop - 22, 13, 12, Color(0xFFD4A373), outline, cell, ox, oy)
            outlinedBox(6, floorTop - 25, 4, 4, Color(0xFFD4A373), outline, cell, ox, oy)
            outlinedBox(15, floorTop - 25, 4, 4, Color(0xFFD4A373), outline, cell, ox, oy)
            px(10, floorTop - 17, 5, 4, Color(0xFFFAEDCD), cell, ox, oy)
        }
        else -> {
            outlinedBox(6, floorTop - 17, 6, 7, Color(0xFFE07A5F), outline, cell, ox, oy)
            px(7, floorTop - 22, 4, 5, Color(0xFF2A9D8F), cell, ox, oy)
            outlinedBox(14, floorTop - 20, 7, 10, Color(0xFFA8DADC), outline, cell, ox, oy)
        }
    }

    outlinedBox(60, floorTop - 10, 32, 22, bedBlanket.darken(0.8f), outline, cell, ox, oy)
    outlinedBox(62, floorTop - 4, 28, 14, bedBlanket, outline, cell, ox, oy)
    outlinedBox(64, floorTop - 8, 9, 6, Color.White, outline, cell, ox, oy)
}

private fun DrawScope.drawKitchenBackScene(
    cols: Int, rows: Int, floorTop: Int, theme: RoomTheme,
    cell: Float, ox: Float, oy: Float
) {
    val outline = Color(0xFF2E1E3E)
    for (y in 0 until floorTop step 6) {
        for (x in 0 until cols step 8) {
            px(x, y, 8, 6, if (((x / 8) + (y / 6)) % 2 == 0) Color(0xFFFFF8EE) else Color(0xFFFFE5D9), cell, ox, oy)
        }
    }
    px(0, floorTop, cols, 1, outline, cell, ox, oy)
    outlinedBox(8, 8, 34, 18, Color(0xFFFFB5A7), outline, cell, ox, oy)
    outlinedBox(10, 10, 14, 14, Color(0xFFFCD5CE), outline, cell, ox, oy)
    outlinedBox(26, 10, 14, 14, Color(0xFFFCD5CE), outline, cell, ox, oy)
    outlinedBox(72, 12, 20, floorTop - 10, Color(0xFFCAF0F8), outline, cell, ox, oy)
    px(74, 30, 16, 1, outline, cell, ox, oy)
    px(75, 22, 2, 5, Color(0xFF48CAE4), cell, ox, oy)
    px(80, 18, 3, 3, Color(0xFFFF4D8D), cell, ox, oy)
    px(85, 22, 3, 3, Color(0xFFFFD166), cell, ox, oy)
    for (y in (floorTop + 1) until rows step 6) {
        for (x in 0 until cols step 8) {
            px(x, y, 8, 6, if (((x / 8) + (y / 6)) % 2 == 0) Color(theme.floorA) else Color(theme.floorB), cell, ox, oy)
        }
    }
}

private fun DrawScope.drawKitchenForegroundTable(
    floorTop: Int, foodId: String, action: PetAnimAction, frame: Int,
    cell: Float, ox: Float, oy: Float
) {
    val outline = Color(0xFF2E1E3E)
    // Ножки стола
    outlinedBox(25, floorTop + 17, 5, 10, Color(0xFFB07D62), outline, cell, ox, oy)
    outlinedBox(66, floorTop + 17, 5, 10, Color(0xFFB07D62), outline, cell, ox, oy)
    // Стол со скатертью СПЕРЕДИ питомца
    outlinedBox(20, floorTop + 8, 56, 11, Color(0xFFFFE5EC), outline, cell, ox, oy)
    px(22, floorTop + 10, 52, 4, Color(0xFFFF8FAB), cell, ox, oy)
    // Тарелка
    outlinedBox(38, floorTop + 6, 20, 6, Color.White, outline, cell, ox, oy)
    px(41, floorTop + 8, 14, 2, Color(0xFFE2E8F0), cell, ox, oy)

    // Спрайт выбранной еды! (если питомец в процессе еды, мигает откусывание)
    val fx = 43
    val fy = floorTop + 1
    val eatingPhase = if (action == PetAnimAction.EATING) frame % 3 else 0
    if (action == PetAnimAction.EATING && eatingPhase == 2) {
        // Маленькие крошки на тарелке
        px(fx + 2, fy + 5, 2, 2, Color(0xFFFFB703), cell, ox, oy)
        px(fx + 6, fy + 4, 2, 2, Color(0xFFFF6B6B), cell, ox, oy)
    } else {
        when (foodId) {
            "onigiri" -> {
                outlinedBox(fx + 2, fy, 6, 3, Color.White, outline, cell, ox, oy)
                outlinedBox(fx, fy + 2, 10, 5, Color.White, outline, cell, ox, oy)
                px(fx + 3, fy + 4, 4, 3, Color(0xFF1B4332), cell, ox, oy) // Нори
            }
            "ramen" -> {
                outlinedBox(fx - 1, fy + 2, 12, 6, Color(0xFFE63946), outline, cell, ox, oy)
                px(fx + 1, fy + 2, 8, 2, Color(0xFFFFE066), cell, ox, oy) // Лапша
                px(fx + 2, fy + 1, 3, 2, Color(0xFFFF9F1C), cell, ox, oy) // Яйцо
                px(fx + 8, fy - 2, 1, 5, Color(0xFF8D5B4C), cell, ox, oy) // Палочки
            }
            "sushi" -> {
                outlinedBox(fx - 1, fy + 2, 6, 5, Color.White, outline, cell, ox, oy)
                px(fx, fy + 1, 4, 3, Color(0xFFFF6B35), cell, ox, oy) // Лосось
                outlinedBox(fx + 6, fy + 2, 6, 5, Color(0xFF1B4332), outline, cell, ox, oy)
                px(fx + 7, fy + 3, 4, 3, Color.White, cell, ox, oy)
                px(fx + 8, fy + 4, 2, 1, Color(0xFFFF4D6D), cell, ox, oy)
            }
            "burger" -> {
                outlinedBox(fx, fy, 10, 3, Color(0xFFF4A261), outline, cell, ox, oy) // Булка верх
                px(fx + 1, fy + 3, 8, 1, Color(0xFF52B788), cell, ox, oy) // Салат
                px(fx + 1, fy + 4, 8, 2, Color(0xFF6F4E37), cell, ox, oy) // Котлета
                outlinedBox(fx, fy + 5, 10, 3, Color(0xFFF4A261), outline, cell, ox, oy)
            }
            "berry" -> {
                outlinedBox(fx + 1, fy + 1, 8, 6, Color(0xFFE63946), outline, cell, ox, oy)
                px(fx + 3, fy - 1, 4, 2, Color(0xFF2A9D8F), cell, ox, oy)
                px(fx + 3, fy + 3, 1, 1, Color(0xFFFFE066), cell, ox, oy)
                px(fx + 6, fy + 4, 1, 1, Color(0xFFFFE066), cell, ox, oy)
            }
            "donut" -> {
                outlinedBox(fx, fy + 1, 10, 6, Color(0xFFF4A261), outline, cell, ox, oy)
                px(fx + 1, fy + 2, 8, 3, Color(0xFFFF6B9D), cell, ox, oy)
                px(fx + 4, fy + 3, 2, 2, Color.White, cell, ox, oy) // Дырочка
            }
            "icecream" -> {
                outlinedBox(fx + 2, fy + 3, 6, 5, Color(0xFFE9C46A), outline, cell, ox, oy)
                outlinedBox(fx + 1, fy - 1, 8, 5, Color(0xFFFFB5E8), outline, cell, ox, oy)
                px(fx + 4, fy - 2, 2, 2, Color(0xFFE63946), cell, ox, oy)
            }
            "cake" -> {
                outlinedBox(fx, fy + 1, 10, 7, Color(0xFFFFF3B0), outline, cell, ox, oy)
                px(fx + 1, fy + 2, 8, 2, Color(0xFFFF6B9D), cell, ox, oy)
                px(fx + 1, fy + 5, 8, 1, Color(0xFFFF6B9D), cell, ox, oy)
                px(fx + 4, fy - 1, 2, 2, Color(0xFFE63946), cell, ox, oy) // Вишенка
            }
        }
    }
}

private fun DrawScope.drawBathroomScene(
    cols: Int, rows: Int, floorTop: Int, frame: Int, cell: Float, ox: Float, oy: Float
) {
    val outline = Color(0xFF23395B)
    for (y in 0 until floorTop step 6) {
        for (x in 0 until cols step 6) {
            px(x, y, 6, 6, if (((x / 6) + (y / 6)) % 2 == 0) Color(0xFFE0FBFC) else Color(0xFFC2F0FC), cell, ox, oy)
        }
    }
    outlinedBox(34, 10, 28, 22, Color(0xFF98C1D9), outline, cell, ox, oy)
    px(36, 12, 24, 18, Color(0xFFF0FBFF), cell, ox, oy)
    px(40, 15, 4, 12, Color(0xAAFFFFFF), cell, ox, oy)
    outlinedBox(10, 18, 12, 18, Color(0xFFFF85B3), outline, cell, ox, oy)
    outlinedBox(70, 24, 16, 4, Color(0xFF98C1D9), outline, cell, ox, oy)
    outlinedBox(74, 16, 5, 8, Color(0xFF7BE495), outline, cell, ox, oy)
    for (y in (floorTop + 1) until rows step 6) {
        for (x in 0 until cols step 6) {
            px(x, y, 6, 6, if (((x / 6) + (y / 6)) % 2 == 0) Color(0xFF90E0EF) else Color(0xFFCAF0F8), cell, ox, oy)
        }
    }
    val bob = frame % 6
    outlinedBox(14, 30 - bob, 5, 5, Color(0xAAFFFFFF), Color(0xFF48CAE4), cell, ox, oy)
    outlinedBox(76, 36 - ((bob + 3) % 6), 6, 6, Color(0xAAFFFFFF), Color(0xFF48CAE4), cell, ox, oy)
}

private fun DrawScope.drawBoutiqueScene(
    cols: Int, rows: Int, floorTop: Int, frame: Int, cell: Float, ox: Float, oy: Float
) {
    val outline = Color(0xFF33183F)
    for (x in 0 until cols step 5) {
        px(x, 0, 5, floorTop, if ((x / 5) % 2 == 0) Color(0xFFFFE5F1) else Color(0xFFF8D0E6), cell, ox, oy)
    }
    outlinedBox(64, 10, 24, 38, Color(0xFFFFD166), outline, cell, ox, oy)
    px(66, 12, 20, 34, Color(0xFFE8F7FF), cell, ox, oy)
    outlinedBox(6, 18, 22, 3, Color(0xFFFFD166), outline, cell, ox, oy)
    outlinedBox(9, 21, 7, 14, Color(0xFFFF6B9D), outline, cell, ox, oy)
    outlinedBox(18, 21, 7, 14, Color(0xFF56CCF2), outline, cell, ox, oy)
    px(0, floorTop, cols, rows - floorTop, Color(0xFF6D3B68), cell, ox, oy)
    outlinedBox(20, floorTop + 8, 56, 12, Color(0xFFFF85B3), outline, cell, ox, oy)
    px(22, floorTop + 10, 52, 6, Color(0xFFFFD166), cell, ox, oy)
    if (frame % 2 == 0) {
        px(30, 6, 4, 4, Color(0xFFFFF59D), cell, ox, oy)
        px(62, 6, 4, 4, Color(0xFFFFF59D), cell, ox, oy)
    }
}

private fun DrawScope.drawArcadeScene(
    cols: Int, rows: Int, floorTop: Int, frame: Int, cell: Float, ox: Float, oy: Float
) {
    val outline = Color(0xFF0E0821)
    px(0, 0, cols, floorTop, Color(0xFF1D1138), cell, ox, oy)
    for (x in 6 until cols step 14) {
        px(x, 8, 2, 2, if (frame % 2 == 0) Color(0xFF00E5FF) else Color(0xFFFF2E93), cell, ox, oy)
        px(x + 6, 16, 2, 2, Color(0xFFFFD166), cell, ox, oy)
    }
    outlinedBox(30, 8, 36, 12, Color(0xFF2D1B5E), Color(0xFF00E5FF), cell, ox, oy)
    px(33, 11, 30, 6, if (frame % 4 < 2) Color(0xFFFF2E93) else Color(0xFF00E5FF), cell, ox, oy)
    outlinedBox(5, 22, 16, floorTop - 16, Color(0xFF6C5CE7), outline, cell, ox, oy)
    px(8, 26, 10, 8, if (frame % 2 == 0) Color(0xFF00E5FF) else Color(0xFF7BE495), cell, ox, oy)
    outlinedBox(75, 22, 16, floorTop - 16, Color(0xFFE84393), outline, cell, ox, oy)
    px(78, 26, 10, 8, if (frame % 2 == 1) Color(0xFFFFD166) else Color(0xFFFF6B9D), cell, ox, oy)
    for (y in (floorTop + 1) until rows step 6) {
        for (x in 0 until cols step 8) {
            px(x, y, 8, 6, if (((x / 8) + (y / 6)) % 2 == 0) Color(0xFF221445) else Color(0xFF2C1B59), cell, ox, oy)
        }
    }
    outlinedBox(18, floorTop + 8, 60, 12, Color(0xFF3A1F6E), Color(0xFF00E5FF), cell, ox, oy)
}

// --- НОВЫЕ ГОРОДСКИЕ СЦЕНЫ ЭТАПА 2 ---
private fun DrawScope.drawSchoolScene(
    cols: Int, rows: Int, floorTop: Int, frame: Int, cell: Float, ox: Float, oy: Float
) {
    val outline = Color(0xFF2B2017)
    px(0, 0, cols, floorTop, Color(0xFFFFF1D6), cell, ox, oy)
    px(0, floorTop - 4, cols, 4, Color(0xFFD4A373), cell, ox, oy)
    // Зелёная школьная доска!
    outlinedBox(24, 10, 48, 24, Color(0xFF1B4332), Color(0xFF8B5E3C), cell, ox, oy)
    px(28, 15, 16, 2, Color.White, cell, ox, oy)
    px(28, 20, 24, 2, Color(0xFFFFD166), cell, ox, oy)
    px(28, 25, 12, 2, Color(0xFF9BF6FF), cell, ox, oy)
    // Парты по бокам
    outlinedBox(6, floorTop - 12, 16, 14, Color(0xFFC77D32), outline, cell, ox, oy)
    outlinedBox(74, floorTop - 12, 16, 14, Color(0xFFC77D32), outline, cell, ox, oy)
    if (frame % 2 == 0) px(10, floorTop - 15, 6, 3, Color(0xFFE63946), cell, ox, oy) // Книжка
    for (y in (floorTop + 1) until rows step 6) {
        for (x in 0 until cols step 8) {
            px(x, y, 8, 6, if (((x / 8) + (y / 6)) % 2 == 0) Color(0xFFDDA15E) else Color(0xFFBC6C25), cell, ox, oy)
        }
    }
}

private fun DrawScope.drawWorkScene(
    cols: Int, rows: Int, floorTop: Int, frame: Int, cell: Float, ox: Float, oy: Float
) {
    val outline = Color(0xFF1B1933)
    px(0, 0, cols, floorTop, Color(0xFFE8EEF5), cell, ox, oy)
    // Большое панорамное окно на город
    outlinedBox(14, 10, 68, 28, Color(0xFF457B9D), outline, cell, ox, oy)
    px(16, 12, 64, 24, Color(0xFF1D3557), cell, ox, oy)
    // Небоскрёбы с огоньками
    px(20, 18, 10, 18, Color(0xFF2B2D42), cell, ox, oy)
    px(34, 15, 12, 21, Color(0xFF3A0CA3), cell, ox, oy)
    px(52, 20, 11, 16, Color(0xFF2B2D42), cell, ox, oy)
    px(23, 21, 2, 2, if (frame % 2 == 0) Color(0xFFFFD166) else Color(0xFF00E5FF), cell, ox, oy)
    px(38, 19, 3, 2, Color(0xFFFF6B9D), cell, ox, oy)
    px(56, 24, 3, 2, Color(0xFFFFD166), cell, ox, oy)
    px(0, floorTop, cols, rows - floorTop, Color(0xFFCBD5E1), cell, ox, oy)
    outlinedBox(20, floorTop + 8, 56, 12, Color(0xFF64748B), outline, cell, ox, oy)
}

private fun DrawScope.drawParkScene(
    cols: Int, rows: Int, floorTop: Int, frame: Int, cell: Float, ox: Float, oy: Float
) {
    val outline = Color(0xFF1B4332)
    px(0, 0, cols, floorTop, Color(0xFF8ECAE6), cell, ox, oy)
    // Солнце и облака
    px(74, 8, 8, 8, Color(0xFFFFD166), cell, ox, oy)
    val cShift = (frame % 12)
    px(12 + cShift, 10, 14, 5, Color.White, cell, ox, oy)
    px(44 + cShift / 2, 14, 12, 4, Color.White, cell, ox, oy)
    // Деревья по краям
    outlinedBox(6, 26, 6, floorTop - 24, Color(0xFF7F4F24), outline, cell, ox, oy)
    outlinedBox(2, 14, 14, 16, Color(0xFF40916C), outline, cell, ox, oy)
    outlinedBox(80, 26, 6, floorTop - 24, Color(0xFF7F4F24), outline, cell, ox, oy)
    outlinedBox(76, 14, 14, 16, Color(0xFF52B788), outline, cell, ox, oy)
    // Зелёный газон + дорожка + цветочки
    px(0, floorTop, cols, rows - floorTop, Color(0xFF74C69D), cell, ox, oy)
    px(20, floorTop + 8, 56, rows - floorTop - 8, Color(0xFFF4E4C1), cell, ox, oy)
    px(8, floorTop + 6, 3, 3, Color(0xFFFF6B9D), cell, ox, oy)
    px(82, floorTop + 8, 3, 3, Color(0xFFFFD166), cell, ox, oy)
}

private fun DrawScope.drawCinemaScene(
    cols: Int, rows: Int, floorTop: Int, frame: Int, cell: Float, ox: Float, oy: Float
) {
    val outline = Color(0xFF140A24)
    px(0, 0, cols, floorTop, Color(0xFF1A0F2E), cell, ox, oy)
    // Экран кинотеатра
    outlinedBox(14, 8, 68, 34, Color(0xFF332669), outline, cell, ox, oy)
    val screenCol = if (frame % 6 < 3) Color(0xFF48CAE4) else Color(0xFFFF85B3)
    px(17, 11, 62, 28, screenCol.darken(0.55f), cell, ox, oy)
    px(44, 18, 8, 8, Color(0xFFFFD166), cell, ox, oy)
    // Красная ковровая дорожка и кресла
    px(0, floorTop, cols, rows - floorTop, Color(0xFF590D22), cell, ox, oy)
    outlinedBox(6, floorTop + 6, 14, 12, Color(0xFFA4133C), outline, cell, ox, oy)
    outlinedBox(76, floorTop + 6, 14, 12, Color(0xFFA4133C), outline, cell, ox, oy)
}

private fun DrawScope.drawTravelScene(
    cols: Int, rows: Int, floorTop: Int, travelId: String, frame: Int, cell: Float, ox: Float, oy: Float
) {
    when (travelId) {
        "tropics" -> {
            px(0, 0, cols, floorTop - 12, Color(0xFF72EFDD), cell, ox, oy)
            px(0, floorTop - 12, cols, 12, Color(0xFF00B4D8), cell, ox, oy) // Море
            px(0, floorTop, cols, rows - floorTop, Color(0xFFF6BD60), cell, ox, oy) // Песок
            outlinedBox(8, 16, 4, floorTop - 14, Color(0xFF8D5B4C), Color(0xFF2B1912), cell, ox, oy)
            px(2, 12, 16, 5, Color(0xFF2A9D8F), cell, ox, oy)
        }
        "cyber" -> {
            px(0, 0, cols, floorTop, Color(0xFF0F0C29), cell, ox, oy)
            px(10, 12, 14, floorTop - 12, Color(0xFF302B63), cell, ox, oy)
            px(72, 16, 14, floorTop - 16, Color(0xFF24243E), cell, ox, oy)
            px(14, 18, 6, 3, if (frame % 2 == 0) Color(0xFF00E5FF) else Color(0xFFFF2E93), cell, ox, oy)
            px(0, floorTop, cols, rows - floorTop, Color(0xFF1A1442), cell, ox, oy)
        }
        "moon" -> {
            px(0, 0, cols, floorTop, Color(0xFF090B1A), cell, ox, oy)
            px(16, 14, 2, 2, Color.White, cell, ox, oy)
            px(74, 10, 8, 8, Color(0xFF48CAE4), cell, ox, oy) // Земля вдалеке!
            px(76, 12, 4, 4, Color(0xFF52B788), cell, ox, oy)
            px(0, floorTop, cols, rows - floorTop, Color(0xFF8D99AE), cell, ox, oy)
            outlinedBox(14, floorTop + 8, 12, 5, Color(0xFF6C757D), Color(0xFF2B2D42), cell, ox, oy)
        }
        else -> {
            // Япония (Сакура + Тории!)
            px(0, 0, cols, floorTop, Color(0xFFFFDFEB), cell, ox, oy)
            // Ворота Тории
            outlinedBox(32, 16, 32, 4, Color(0xFFE63946), Color(0xFF2B1018), cell, ox, oy)
            outlinedBox(38, 20, 4, floorTop - 20, Color(0xFFE63946), Color(0xFF2B1018), cell, ox, oy)
            outlinedBox(54, 20, 4, floorTop - 20, Color(0xFFE63946), Color(0xFF2B1018), cell, ox, oy)
            px(0, floorTop, cols, rows - floorTop, Color(0xFFFFB5D8), cell, ox, oy)
            val p = frame % 8
            px(15, 16 + p, 3, 2, Color(0xFFFF4D8D), cell, ox, oy)
            px(78, 12 + ((p + 4) % 8), 3, 2, Color(0xFFFF4D8D), cell, ox, oy)
        }
    }
}

private fun DrawScope.drawSceneActionEffects(
    action: PetAnimAction,
    isDeceased: Boolean,
    floorTop: Int,
    roamX: Int,
    frame: Int,
    cell: Float,
    ox: Float,
    oy: Float
) {
    val lift = (frame % 6) * 2
    when {
        isDeceased -> {
            val sp = if (frame % 2 == 0) Color(0xFFFFF59D) else Color(0xFFA8E6FF)
            px(roamX + 12, floorTop - 36 - lift, 2, 2, sp, cell, ox, oy)
            px(roamX + 50, floorTop - 32 - lift, 2, 2, sp, cell, ox, oy)
        }
        action == PetAnimAction.PETTING || action == PetAnimAction.PRAISING -> {
            val hx = roamX + 28
            val hy = floorTop - 36 - lift
            px(hx, hy, 2, 2, Color(0xFFFF4D8D), cell, ox, oy)
            px(hx + 3, hy, 2, 2, Color(0xFFFF4D8D), cell, ox, oy)
            px(hx + 1, hy + 2, 3, 2, Color(0xFFFF4D8D), cell, ox, oy)
        }
        action == PetAnimAction.SINGING || action == PetAnimAction.DANCING -> {
            px(roamX + 12, floorTop - 34 - lift, 2, 6, Color(0xFF00E5FF), cell, ox, oy)
            px(roamX + 12, floorTop - 34 - lift, 5, 2, Color(0xFF00E5FF), cell, ox, oy)
            px(roamX + 50, floorTop - 30 - lift, 2, 6, Color(0xFFFF6B9D), cell, ox, oy)
            px(roamX + 50, floorTop - 30 - lift, 5, 2, Color(0xFFFF6B9D), cell, ox, oy)
        }
        action == PetAnimAction.HEALING -> {
            px(roamX + 48, floorTop - 34 - lift, 2, 6, Color(0xFF2ECC71), cell, ox, oy)
            px(roamX + 46, floorTop - 32 - lift, 6, 2, Color(0xFF2ECC71), cell, ox, oy)
        }
        action == PetAnimAction.EATING -> {
            px(roamX + 22, floorTop - 6 + (lift % 4), 2, 2, Color(0xFFFFB703), cell, ox, oy)
            px(roamX + 40, floorTop - 8 + (lift % 4), 2, 2, Color(0xFFFF6B6B), cell, ox, oy)
        }
        action == PetAnimAction.HATCHED -> {
            val cols = listOf(Color(0xFFFFD166), Color(0xFFFF6B9D), Color(0xFF00E5FF), Color(0xFF7BE495))
            for (k in 0..7) {
                px(roamX + 10 + k * 6, floorTop - 38 + ((frame + k) % 6) * 3, 3, 3, cols[k % cols.size], cell, ox, oy)
            }
        }
    }
}

// =====================================================================
// ПЛАВНЫЙ TOUMA PET ДВИЖОК 64×64 (БЕЗ КВАДРАТНОСТИ!)
// =====================================================================

private fun DrawScope.drawCurvedPet64(
    pet: PetDefinition,
    palette: PetPalette,
    stage: PetStage,
    frame: Int,
    mood: PetMood,
    isSleeping: Boolean,
    isSick: Boolean,
    isDeceased: Boolean,
    eggTaps: Int,
    equipped: EquippedWardrobe,
    isSilhouette: Boolean,
    cell: Float,
    ox: Float,
    oy: Float
) {
    val outline = when {
        isSilhouette -> Color(0xFF140D26)
        isDeceased -> Color(0xFF5D6B9E)
        else -> Color(palette.outline)
    }
    val main = when {
        isSilhouette -> Color(0xFF2B204A)
        isDeceased -> Color(0xFFDCEEFF)
        isSick -> Color(palette.primary).copy(alpha = 0.78f)
        else -> Color(palette.primary)
    }
    val light = if (isSilhouette) Color(0xFF392B61) else if (isDeceased) Color(0xFFF5FAFF) else Color(palette.light)
    val accent = if (isSilhouette) Color(0xFF2B204A) else if (isDeceased) Color(0xFFA8DADC) else Color(palette.accent)
    val shade = main.darken(0.84f)
    val blush = if (isSilhouette) main else Color(0xFFFF6B9D)

    val buf = PixelBuffer64()

    // --- ПЛАВНОЕ ЯЙЦО ---
    if (stage == PetStage.EGG) {
        val wobble = if (eggTaps > 0 && frame % 2 == 0) 2f else 0f
        buf.fillEllipse(32f + wobble, 34f, 13f, 16f, main)
        buf.fillEllipse(32f + wobble, 37f, 14f, 13f, main)
        buf.applyShadeOnFilled(shade, light, 34)
        if (!isSilhouette) {
            buf.fillEllipse(26f + wobble, 32f, 2.5f, 3f, accent)
            buf.fillEllipse(37f + wobble, 38f, 3f, 3f, light)
            buf.fillEllipse(27f + wobble, 24f, 2f, 2f, Color.White)
            if (eggTaps > 0) {
                buf.fillCapsule(30f + wobble, 28f, 34f + wobble, 34f, 0.8f, outline)
                if (eggTaps >= 3) buf.fillCapsule(28f + wobble, 33f, 36f + wobble, 36f, 0.8f, outline)
            }
        }
        buf.renderWithAutoOutline(this, outline, cell, ox, oy)
        return
    }

    val bounce = when {
        isDeceased -> if (frame % 4 < 2) -2 else 0
        isSleeping -> 0
        mood == PetMood.ECSTATIC || mood == PetMood.PLAYFUL -> if (frame % 2 == 0) -2 else 0
        else -> if (frame % 4 == 1) -1 else 0
    }
    val blink = !isSleeping && !isDeceased && (frame % 16 == 0)
    val bodyY = (39 + bounce).coerceIn(30, 44)
    val headCy = (25 + bounce).coerceIn(18, 32)
    val headY = headCy - 11
    val eyeY = headCy - 1
    val headW = when (pet.head) {
        HeadShape.WIDE -> 30
        HeadShape.OVAL -> 24
        else -> 28
    }
    val hx = 32 - headW / 2

    if (isDeceased) {
        buf.fillEllipse(32f, (headY - 6).toFloat(), 8f, 2f, Color(0xFFFFE066))
    }

    // 1. Гардероб ЗА питомцем
    if (!isSilhouette) {
        drawWardrobeBack64(equipped, bodyY - 2, frame, cell, ox, oy)
    }

    // 2. Хвосты / крылья / спина в буфер (чтобы у них был единый плавный контур!)
    val wag = if (frame % 2 == 0) 0f else 2f
    when (pet.back) {
        BackFeature.ANGEL_WINGS -> {
            val flap = if (frame % 2 == 0) 0f else -2f
            buf.fillEllipse(14f, bodyY + 4f + flap, 6f, 4.5f, Color.White)
            buf.fillEllipse(50f, bodyY + 4f + flap, 6f, 4.5f, Color.White)
        }
        BackFeature.BAT_WINGS -> {
            val flap = if (frame % 2 == 0) 0f else -2f
            buf.fillEllipse(14f, bodyY + 3f + flap, 6f, 4f, accent)
            buf.fillEllipse(50f, bodyY + 3f + flap, 6f, 4f, accent)
        }
        BackFeature.FAIRY_WINGS -> {
            val flap = if (frame % 2 == 0) 0f else -1.5f
            buf.fillEllipse(14f, bodyY + 2f + flap, 6f, 5f, Color(0xFFB8F2FF))
            buf.fillEllipse(50f, bodyY + 2f + flap, 6f, 5f, Color(0xFFB8F2FF))
            buf.fillEllipse(16f, bodyY + 8f + flap, 4f, 3f, Color(0xFFE0FBFC))
            buf.fillEllipse(48f, bodyY + 8f + flap, 4f, 3f, Color(0xFFE0FBFC))
        }
        BackFeature.FLUFFY_TAIL -> {
            buf.fillEllipse(46f, bodyY + 7f - wag, 6.5f, 5.5f, main)
            buf.fillEllipse(47f, bodyY + 7f - wag, 3.5f, 3f, light)
        }
        BackFeature.CAT_TAIL -> {
            buf.fillCapsule(40f, bodyY + 10f, 50f, bodyY + 5f - wag, 2.2f, accent)
        }
        BackFeature.DINO_SPIKES -> {
            buf.fillEllipse(44f, bodyY + 2f, 3f, 2f, accent)
            buf.fillEllipse(45f, bodyY + 7f, 3f, 2f, accent)
            buf.fillEllipse(44f, bodyY + 12f, 2.5f, 2f, accent)
        }
        BackFeature.AXO_GILLS -> {
            for (g in -1..1) {
                buf.fillCapsule(14f, headCy + g * 4f, 20f, headCy + g * 3f, 1.8f, accent)
                buf.fillCapsule(44f, headCy + g * 3f, 50f, headCy + g * 4f, 1.8f, accent)
            }
        }
        BackFeature.TURTLE_CARAPACE -> {
            buf.fillEllipse(32f, bodyY + 6f, 14f, 9f, accent)
            buf.fillEllipse(32f, bodyY + 5f, 10f, 6f, accent.lighten(0.22f))
        }
        BackFeature.JETPACK -> {
            buf.fillRoundedRect(16, bodyY + 1, 6, 12, 2, Color(0xFF8D99AE))
            buf.fillRoundedRect(42, bodyY + 1, 6, 12, 2, Color(0xFF8D99AE))
        }
        else -> {}
    }

    // 3. Плавное округлое тельце по 16 силуэтам
    val walk = if (frame % 4 == 1) 1f else 0f
    when (pet.silhouette) {
        PetSilhouette.SLIME_JELLY -> {
            buf.fillEllipse(32f, bodyY + 4f, 15f, 10f, main)
            buf.fillEllipse(32f, bodyY + 7f, 17f, 7f, main)
        }
        PetSilhouette.GHOST_FLOAT -> {
            buf.fillEllipse(32f, bodyY + 3f, 13f, 10f, main)
            for (gx in 22..42 step 5) {
                val wv = if ((gx + frame) % 2 == 0) 2f else 0f
                buf.fillEllipse(gx.toFloat(), bodyY + 11f + wv, 3f, 3.5f, main)
            }
        }
        PetSilhouette.OCTO_TENTACLES -> {
            buf.fillEllipse(32f, bodyY + 4f, 12f, 8f, main)
            for (tx in 20..44 step 6) {
                val sh = if ((tx + frame) % 2 == 0) 0f else 2f
                buf.fillCapsule(tx.toFloat(), bodyY + 9f, tx.toFloat(), bodyY + 15f + sh, 2.5f, accent)
            }
        }
        PetSilhouette.WHALE_FIN -> {
            buf.fillEllipse(32f, bodyY + 5f, 14f, 8.5f, main)
            buf.fillEllipse(16f, bodyY + 6f, 4f, 2.5f, accent)
            buf.fillEllipse(48f, bodyY + 6f, 4f, 2.5f, accent)
            buf.fillEllipse(28f, bodyY + 14f, 4f, 2.5f, accent)
            buf.fillEllipse(36f, bodyY + 14f, 4f, 2.5f, accent)
        }
        PetSilhouette.BIRD_OWL -> {
            buf.fillEllipse(32f, bodyY + 5f, 13f, 9.5f, main)
            buf.fillEllipse(18f, bodyY + 5f, 3.5f, 6f, accent)
            buf.fillEllipse(46f, bodyY + 5f, 3.5f, 6f, accent)
            buf.fillEllipse(26f, bodyY + 15f, 3f, 2f, Color(0xFFFF9F1C))
            buf.fillEllipse(38f, bodyY + 15f, 3f, 2f, Color(0xFFFF9F1C))
        }
        PetSilhouette.CAPYBARA_ROUND -> {
            buf.fillEllipse(32f, bodyY + 6f, 15f, 9f, main)
            buf.fillEllipse(23f, bodyY + 15f - walk, 4f, 3f, shade)
            buf.fillEllipse(41f, bodyY + 15f, 4f, 3f, shade)
        }
        PetSilhouette.MECHA_BOT -> {
            buf.fillRoundedRect(20, bodyY - 1, 24, 15, 4, main)
            buf.fillCapsule(16f, bodyY + 3f, 16f, bodyY + 9f, 2.5f, accent)
            buf.fillCapsule(48f, bodyY + 3f, 48f, bodyY + 9f, 2.5f, accent)
            buf.fillRoundedRect(22, bodyY + 13, 7, 5, 2, accent)
            buf.fillRoundedRect(35, bodyY + 13, 7, 5, 2, accent)
        }
        PetSilhouette.HERO_CHIBI -> {
            // Милое чиби-платьице/костюм как у персонажей на коробке Touma Pet!
            val outfitCol = when (pet.heroSpecial) {
                HeroSpecial.CYBER_DIVA -> Color(0xFFE8EEF5)
                HeroSpecial.GOTH_BUNNY -> Color(0xFF2B2438)
                HeroSpecial.COSMIC_WITCH -> Color(0xFF5A189A)
                HeroSpecial.SAMURAI_CAT -> Color(0xFFE63946)
                else -> accent
            }
            buf.fillEllipse(32f, bodyY + 6f, 11f, 9f, outfitCol)
            buf.fillCapsule(20f, bodyY + 2f, 17f, bodyY + 8f, 2.6f, main)
            buf.fillCapsule(44f, bodyY + 2f, 47f, bodyY + 8f, 2.6f, main)
            buf.fillEllipse(26f, bodyY + 15f - walk, 3.8f, 3f, main)
            buf.fillEllipse(38f, bodyY + 15f, 3.8f, 3f, main)
        }
        else -> {
            // Плавный грушевидный чиби-торсик + округлые ручки и ножки!
            buf.fillEllipse(32f, bodyY + 6f, 11.5f, 9f, main)
            buf.fillCapsule(20f, bodyY + 3f, 17f, bodyY + 8f, 2.8f, main)
            buf.fillCapsule(44f, bodyY + 3f, 47f, bodyY + 8f, 2.8f, main)
            buf.fillEllipse(26f, bodyY + 15f - walk, 3.8f, 3f, main)
            buf.fillEllipse(38f, bodyY + 15f, 3.8f, 3f, main)
        }
    }

    // Узор пузика
    if (!isSilhouette && pet.silhouette != PetSilhouette.MECHA_BOT) {
        when (pet.marking) {
            MarkingType.TUXEDO -> buf.fillEllipse(32f, bodyY + 6f, 6.5f, 5.5f, Color.White)
            MarkingType.HEART_CHEST -> {
                buf.fillEllipse(30f, bodyY + 5f, 2f, 2f, accent)
                buf.fillEllipse(34f, bodyY + 5f, 2f, 2f, accent)
                buf.fillEllipse(32f, bodyY + 7f, 2.5f, 2f, accent)
            }
            else -> buf.fillEllipse(32f, bodyY + 7f, 7f, 5f, light)
        }
    }

    // 4. Плавные ушки / рожки (до головы, чтобы естественно сливались с контуром!)
    when (pet.ears) {
        EarType.CAT -> {
            buf.fillCapsule(21f, headCy - 6f, 19f, headCy - 15f, 3.5f, main)
            buf.fillCapsule(43f, headCy - 6f, 45f, headCy - 15f, 3.5f, main)
            if (!isSilhouette) {
                buf.fillCapsule(21f, headCy - 7f, 20f, headCy - 13f, 1.5f, accent)
                buf.fillCapsule(43f, headCy - 7f, 44f, headCy - 13f, 1.5f, accent)
            }
        }
        EarType.BUNNY -> {
            buf.fillCapsule(24f, headCy - 8f, 22f, headCy - 20f, 3.2f, main)
            buf.fillCapsule(40f, headCy - 8f, 42f, headCy - 20f, 3.2f, main)
            if (!isSilhouette) {
                buf.fillCapsule(24f, headCy - 9f, 22.5f, headCy - 18f, 1.4f, accent)
                buf.fillCapsule(40f, headCy - 9f, 41.5f, headCy - 18f, 1.4f, accent)
            }
        }
        EarType.BEAR, EarType.MONKEY_EARS -> {
            val dist = if (pet.ears == EarType.MONKEY_EARS) 15f else 11f
            val dy = if (pet.ears == EarType.MONKEY_EARS) -2f else -10f
            buf.fillEllipse(32f - dist, headCy + dy, 4.5f, 4.5f, main)
            buf.fillEllipse(32f + dist, headCy + dy, 4.5f, 4.5f, main)
            if (!isSilhouette) {
                buf.fillEllipse(32f - dist, headCy + dy, 2.2f, 2.2f, accent)
                buf.fillEllipse(32f + dist, headCy + dy, 2.2f, 2.2f, accent)
            }
        }
        EarType.PUPPY -> {
            buf.fillCapsule(17f, headCy - 2f, 15f, headCy + 8f, 3.2f, accent)
            buf.fillCapsule(47f, headCy - 2f, 49f, headCy + 8f, 3.2f, accent)
        }
        EarType.FOX -> {
            buf.fillCapsule(21f, headCy - 6f, 18f, headCy - 16f, 3.8f, main)
            buf.fillCapsule(43f, headCy - 6f, 46f, headCy - 16f, 3.8f, main)
            if (!isSilhouette) {
                buf.fillEllipse(18.5f, headCy - 15f, 2f, 2f, Color.White)
                buf.fillEllipse(45.5f, headCy - 15f, 2f, 2f, Color.White)
            }
        }
        EarType.DRAGON_HORNS -> {
            buf.fillCapsule(23f, headCy - 8f, 20f, headCy - 16f, 2.2f, Color(0xFFFFD166))
            buf.fillCapsule(41f, headCy - 8f, 44f, headCy - 16f, 2.2f, Color(0xFFFFD166))
        }
        EarType.UNICORN_HORN -> {
            buf.fillCapsule(32f, headCy - 9f, 32f, headCy - 19f, 2.2f, Color(0xFFFFD166))
        }
        EarType.HEART_ANTENNA -> {
            buf.fillCapsule(32f, headCy - 9f, 32f, headCy - 15f, 1.2f, accent)
            buf.fillEllipse(30f, headCy - 16f, 2.5f, 2.5f, Color(0xFFFF4D8D))
            buf.fillEllipse(34f, headCy - 16f, 2.5f, 2.5f, Color(0xFFFF4D8D))
        }
        EarType.FROG_EYES -> {
            buf.fillEllipse(24f, headCy - 10f, 4.5f, 4.5f, main)
            buf.fillEllipse(40f, headCy - 10f, 4.5f, 4.5f, main)
        }
        EarType.ANTENNA -> {
            buf.fillCapsule(32f, headCy - 9f, 32f, headCy - 16f, 1.2f, outline)
            buf.fillEllipse(32f, headCy - 17f, 2.8f, 2.8f, accent)
        }
        EarType.BAT_EARS -> {
            buf.fillCapsule(21f, headCy - 7f, 18f, headCy - 17f, 3.2f, accent)
            buf.fillCapsule(43f, headCy - 7f, 46f, headCy - 17f, 3.2f, accent)
        }
        EarType.LEAF_SPROUT -> {
            buf.fillCapsule(32f, headCy - 10f, 32f, headCy - 16f, 1.1f, Color(0xFF2D6A4F))
            buf.fillEllipse(28f, headCy - 17f, 3.2f, 1.8f, Color(0xFF52B788))
            buf.fillEllipse(36f, headCy - 16f, 3.2f, 1.8f, Color(0xFF74C69D))
        }
        EarType.ANTLERS -> {
            buf.fillCapsule(24f, headCy - 9f, 20f, headCy - 18f, 1.5f, Color(0xFF8D5B4C))
            buf.fillCapsule(40f, headCy - 9f, 44f, headCy - 18f, 1.5f, Color(0xFF8D5B4C))
        }
        EarType.OWL_TUFTS -> {
            buf.fillEllipse(22f, headCy - 10f, 3.5f, 3f, accent)
            buf.fillEllipse(42f, headCy - 10f, 3.5f, 3f, accent)
        }
        EarType.FIN_EARS -> {
            buf.fillEllipse(16f, headCy + 2f, 3.5f, 5f, accent)
            buf.fillEllipse(48f, headCy + 2f, 3.5f, 5f, accent)
        }
        EarType.BUG_FEELERS -> {
            buf.fillCapsule(25f, headCy - 9f, 22f, headCy - 17f, 1.1f, outline)
            buf.fillCapsule(39f, headCy - 9f, 42f, headCy - 17f, 1.1f, outline)
            buf.fillEllipse(22f, headCy - 17f, 2f, 2f, accent)
            buf.fillEllipse(42f, headCy - 17f, 2f, 2f, accent)
        }
        EarType.NONE -> {}
    }

    // 5. ПЛАВНАЯ ГОЛОВА ПО 10 ФОРМАМ (включая Звезду, Солнышко, Облако, Каплю и др.)
    when (pet.head) {
        HeadShape.STAR_FIVE -> {
            buf.fillStarHead(32f, headCy.toFloat(), 17.5f, 10.5f, 5, main)
        }
        HeadShape.SUN_SPIKY -> {
            buf.fillStarHead(32f, headCy.toFloat(), 16.5f, 12.0f, 9, main)
        }
        HeadShape.CLOUD -> {
            buf.fillEllipse(32f, headCy.toFloat(), 13f, 10.5f, main)
            buf.fillEllipse(22f, headCy + 2f, 5.5f, 5.5f, main)
            buf.fillEllipse(42f, headCy + 2f, 5.5f, 5.5f, main)
            buf.fillEllipse(32f, headCy - 8f, 6f, 4.5f, main)
        }
        HeadShape.PEAR -> {
            buf.fillEllipse(32f, headCy + 2f, 14f, 10f, main)
            buf.fillEllipse(32f, headCy - 4f, 10.5f, 7.5f, main)
        }
        HeadShape.OVAL -> {
            buf.fillEllipse(32f, headCy.toFloat(), 12.5f, 12.5f, main)
        }
        HeadShape.WIDE -> {
            buf.fillEllipse(32f, headCy + 1f, 15.5f, 10.5f, main)
        }
        HeadShape.DIAMOND -> {
            buf.fillEllipse(32f, headCy.toFloat(), 13f, 11f, main)
            buf.fillEllipse(20f, headCy + 2f, 4f, 3.5f, main)
            buf.fillEllipse(44f, headCy + 2f, 4f, 3.5f, main)
        }
        HeadShape.CHIBI_HUMAN -> {
            buf.fillEllipse(32f, headCy.toFloat(), 13.5f, 11.5f, main)
        }
        HeadShape.SQUARE_TV -> {
            buf.fillRoundedRect(17, headCy - 11, 30, 22, 5, main)
        }
        HeadShape.ROUND -> {
            buf.fillEllipse(32f, headCy.toFloat(), 14f, 11.5f, main)
        }
    }

    // Мягкие блики и тени на всех формах
    if (!isSilhouette) {
        buf.applyShadeOnFilled(shade, light, headCy)
    }

    // 6. ПРИЧЁСКИ И ГЕРОИ (в буфер — тоже с единым плавным контуром!)
    val sway = if (frame % 4 < 2) 0f else 1.5f
    when (pet.hair) {
        HairStyle.TWIN_TAILS -> {
            val hairCol = Color(0xFF2EC4B6)
            val tieCol = Color(0xFFFF2E93)
            buf.fillEllipse(32f, headCy - 8f, 13f, 5.5f, hairCol)
            buf.fillCapsule(17f, headCy - 4f, 12f - sway, headCy + 18f, 3.2f, hairCol)
            buf.fillCapsule(47f, headCy - 4f, 52f + sway, headCy + 18f, 3.2f, hairCol)
            if (!isSilhouette) {
                buf.fillEllipse(18f, headCy - 5f, 2.2f, 2.2f, tieCol)
                buf.fillEllipse(46f, headCy - 5f, 2.2f, 2.2f, tieCol)
            }
        }
        HairStyle.JESTER_HOOD -> {
            val hood = Color(0xFF2B2438)
            val pink = Color(0xFFFF6B9D)
            buf.fillEllipse(32f, headCy - 7f, 14f, 6f, hood)
            buf.fillCapsule(21f, headCy - 8f, 18f, headCy - 20f, 4f, hood)
            buf.fillCapsule(43f, headCy - 8f, 46f, headCy - 20f, 4f, hood)
            if (!isSilhouette) {
                buf.fillEllipse(18f, headCy - 20f, 2f, 2f, pink)
                buf.fillEllipse(46f, headCy - 20f, 2f, 2f, pink)
                buf.fillEllipse(32f, headCy - 7f, 2.5f, 2f, pink)
            }
        }
        HairStyle.CHEF_HAT -> {
            buf.fillEllipse(32f, headCy - 14f, 10f, 5f, Color.White)
            buf.fillRoundedRect(23, headCy - 13, 18, 6, 2, Color.White)
        }
        HairStyle.SWOOP_HAIR -> {
            buf.fillEllipse(32f, headCy - 8f, 13.5f, 5.5f, accent)
            buf.fillEllipse(24f, headCy - 5f, 5f, 3f, accent)
            buf.fillEllipse(41f, headCy - 5f, 4f, 3f, accent)
        }
        HairStyle.HIGH_PONYTAIL -> {
            buf.fillEllipse(32f, headCy - 8f, 13f, 5f, accent)
            buf.fillCapsule(44f, headCy - 6f, 49f + sway, headCy + 10f, 3.5f, accent)
        }
        HairStyle.KABUTO_HELM -> {
            buf.fillEllipse(32f, headCy - 7f, 15f, 5.5f, Color(0xFF2B2D42))
            buf.fillCapsule(27f, headCy - 14f, 32f, headCy - 11f, 1.5f, Color(0xFFFFD166))
            buf.fillCapsule(37f, headCy - 14f, 32f, headCy - 11f, 1.5f, Color(0xFFFFD166))
        }
        HairStyle.SHINOBI_COWL -> {
            buf.fillEllipse(32f, headCy - 8f, 14f, 5f, Color(0xFF2B2D42))
            buf.fillEllipse(32f, headCy + 7f, 11f, 4f, Color(0xFFE63946))
        }
        HairStyle.MANDARIN_TOP -> {
            buf.fillEllipse(32f, headCy - 13f, 4f, 3.2f, Color(0xFFFF8500))
            buf.set(32, headCy - 17, Color(0xFF2A9D8F))
            buf.set(33, headCy - 17, Color(0xFF2A9D8F))
        }
        HairStyle.FLOWER_CROWN -> {
            buf.fillEllipse(24f, headCy - 10f, 3f, 2.5f, Color(0xFFFF6B9D))
            buf.fillEllipse(32f, headCy - 11f, 3f, 2.5f, Color(0xFFFFD166))
            buf.fillEllipse(40f, headCy - 10f, 3f, 2.5f, Color(0xFF7BE495))
        }
        HairStyle.FLUFF_TUFT -> buf.fillEllipse(32f, headCy - 11f, 4.5f, 3f, light)
        HairStyle.LION_MANE -> buf.fillStarHead(32f, headCy.toFloat(), 17.5f, 13.5f, 10, accent)
        HairStyle.CREST_FIN -> buf.fillEllipse(32f, headCy - 12f, 3f, 4.5f, accent)
        HairStyle.WATER_SPOUT -> {
            val sp = if (frame % 2 == 0) 0f else -2f
            buf.fillCapsule(32f, headCy - 10f, 32f, headCy - 16f + sp, 1.2f, Color(0xFF48CAE4))
            buf.fillEllipse(29f, headCy - 17f + sp, 2.5f, 1.5f, Color(0xFFADE8F4))
            buf.fillEllipse(35f, headCy - 17f + sp, 2.5f, 1.5f, Color(0xFFADE8F4))
        }
        HairStyle.TV_SCREEN, HairStyle.NONE -> {}
    }

    // Отрисовываем буфер с плавным 1px контуром!
    buf.renderWithAutoOutline(this, outline, cell, ox, oy)

    // 7. Лицо поверх плавной головы (глаза с бликами, щёчки, ротик "w")
    if (!isSilhouette) {
        if (pet.heroSpecial == HeroSpecial.RETRO_BOT) {
            outlinedBox(21, headCy - 6, 22, 13, Color(0xFF141834), outline, cell, ox, oy)
            val glow = when (mood) {
                PetMood.SICK, PetMood.GRUMPY -> Color(0xFFFF5E57)
                PetMood.IN_LOVE -> Color(0xFFFF6B9D)
                else -> Color(0xFF00F5D4)
            }
            when (mood) {
                PetMood.SLEEPY -> {
                    px(25, headCy - 1, 4, 1, glow, cell, ox, oy)
                    px(35, headCy - 1, 4, 1, glow, cell, ox, oy)
                }
                PetMood.IN_LOVE -> {
                    px(25, headCy - 3, 4, 3, glow, cell, ox, oy)
                    px(35, headCy - 3, 4, 3, glow, cell, ox, oy)
                }
                else -> {
                    px(25, headCy - 3, 3, 4, glow, cell, ox, oy)
                    px(36, headCy - 3, 3, 4, glow, cell, ox, oy)
                }
            }
            px(30, headCy + 2, 4, 2, glow, cell, ox, oy)
        } else {
            drawFace64(pet, outline, accent, blush, hx, eyeY, headW, blink, isSleeping, isSick, isDeceased, mood, cell, ox, oy)
        }
    }

    // 8. Гардероб СПЕРЕДИ
    if (!isSilhouette) {
        drawWardrobeFront64(equipped, hx, headY, eyeY, bodyY, headW, frame, cell, ox, oy)
    }
}

private fun DrawScope.drawFace64(
    pet: PetDefinition,
    outline: Color, accent: Color, blush: Color,
    hx: Int, eyeY: Int, headW: Int,
    blink: Boolean, isSleeping: Boolean, isSick: Boolean, isDeceased: Boolean,
    mood: PetMood,
    cell: Float, ox: Float, oy: Float
) {
    val lx = hx + 5
    val rx = hx + headW - 10

    // Румяные щёчки
    px(lx - 1, eyeY + 6, 4, 2, blush, cell, ox, oy)
    px(rx + 2, eyeY + 6, 4, 2, blush, cell, ox, oy)

    when {
        isSleeping || isDeceased -> {
            px(lx, eyeY + 3, 5, 1, outline, cell, ox, oy)
            px(lx + 1, eyeY + 4, 3, 1, outline, cell, ox, oy)
            px(rx, eyeY + 3, 5, 1, outline, cell, ox, oy)
            px(rx + 1, eyeY + 4, 3, 1, outline, cell, ox, oy)
        }
        isSick -> {
            px(lx, eyeY + 1, 2, 2, outline, cell, ox, oy); px(lx + 3, eyeY + 1, 2, 2, outline, cell, ox, oy); px(lx + 2, eyeY + 3, 1, 1, outline, cell, ox, oy)
            px(rx, eyeY + 1, 2, 2, outline, cell, ox, oy); px(rx + 3, eyeY + 1, 2, 2, outline, cell, ox, oy); px(rx + 2, eyeY + 3, 1, 1, outline, cell, ox, oy)
        }
        blink -> {
            px(lx, eyeY + 3, 5, 2, outline, cell, ox, oy)
            px(rx, eyeY + 3, 5, 2, outline, cell, ox, oy)
        }
        mood == PetMood.IN_LOVE -> {
            px(lx, eyeY + 1, 2, 2, Color(0xFFFF3377), cell, ox, oy); px(lx + 3, eyeY + 1, 2, 2, Color(0xFFFF3377), cell, ox, oy); px(lx + 1, eyeY + 3, 3, 2, Color(0xFFFF3377), cell, ox, oy)
            px(rx, eyeY + 1, 2, 2, Color(0xFFFF3377), cell, ox, oy); px(rx + 3, eyeY + 1, 2, 2, Color(0xFFFF3377), cell, ox, oy); px(rx + 1, eyeY + 3, 3, 2, Color(0xFFFF3377), cell, ox, oy)
        }
        else -> {
            // Скруглённые блестящие глазки 5×6
            px(lx + 1, eyeY, 3, 6, outline, cell, ox, oy)
            px(lx, eyeY + 1, 5, 4, outline, cell, ox, oy)
            px(rx + 1, eyeY, 3, 6, outline, cell, ox, oy)
            px(rx, eyeY + 1, 5, 4, outline, cell, ox, oy)
            // Цветная радужка
            px(lx + 1, eyeY + 4, 3, 1, accent, cell, ox, oy)
            px(rx + 1, eyeY + 4, 3, 1, accent, cell, ox, oy)
            // Яркий блик
            px(lx + 1, eyeY + 1, 2, 2, Color.White, cell, ox, oy)
            px(rx + 1, eyeY + 1, 2, 2, Color.White, cell, ox, oy)
            px(lx + 3, eyeY + 4, 1, 1, Color.White, cell, ox, oy)
            px(rx + 3, eyeY + 4, 1, 1, Color.White, cell, ox, oy)
            // Реснички
            px(lx - 1, eyeY, 1, 2, outline, cell, ox, oy)
            px(rx + 5, eyeY, 1, 2, outline, cell, ox, oy)
            if (mood == PetMood.GRUMPY) {
                px(lx + 1, eyeY - 2, 4, 1, outline, cell, ox, oy)
                px(rx, eyeY - 2, 4, 1, outline, cell, ox, oy)
            }
        }
    }

    val cx = hx + headW / 2
    when (pet.face) {
        FaceStyle.BEAK -> {
            outlinedBox(cx - 2, eyeY + 4, 5, 4, Color(0xFFFF9F1C), outline, cell, ox, oy)
        }
        FaceStyle.SNOUT -> {
            outlinedBox(cx - 4, eyeY + 3, 8, 6, Color.White, outline, cell, ox, oy)
            px(cx - 1, eyeY + 4, 2, 2, outline, cell, ox, oy)
        }
        FaceStyle.WHISKERS -> {
            px(hx + 1, eyeY + 5, 3, 1, outline, cell, ox, oy)
            px(hx + 1, eyeY + 7, 3, 1, outline, cell, ox, oy)
            px(hx + headW - 4, eyeY + 5, 3, 1, outline, cell, ox, oy)
            px(hx + headW - 4, eyeY + 7, 3, 1, outline, cell, ox, oy)
            px(cx - 2, eyeY + 6, 4, 1, outline, cell, ox, oy)
        }
        FaceStyle.FANG -> {
            px(cx - 3, eyeY + 5, 6, 1, outline, cell, ox, oy)
            px(cx - 2, eyeY + 6, 1, 2, Color.White, cell, ox, oy)
            px(cx + 1, eyeY + 6, 1, 2, Color.White, cell, ox, oy)
        }
        else -> {
            px(cx - 3, eyeY + 5, 2, 1, outline, cell, ox, oy)
            px(cx - 1, eyeY + 6, 2, 1, outline, cell, ox, oy)
            px(cx + 1, eyeY + 5, 2, 1, outline, cell, ox, oy)
        }
    }
}
