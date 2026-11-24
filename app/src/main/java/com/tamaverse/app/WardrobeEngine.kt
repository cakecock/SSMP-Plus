package com.tamaverse.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

enum class WardrobeSlot(val title: String, val emoji: String) {
    HEAD("Голова", "👒"),
    FACE("Лицо", "👓"),
    TORSO("Одежда", "👕"),
    SHOES("Обувь", "👟"),
    BACK("Спина", "🎒"),
    HAND("В лапках", "🎤"),
    AURA("Эффекты", "✨")
}

enum class WardrobeShape(val baseName: String, val emoji: String, val slot: WardrobeSlot) {
    // HEAD (9)
    BOW("Бантик", "🎀", WardrobeSlot.HEAD),
    CAP("Кепка", "🧢", WardrobeSlot.HEAD),
    FROG_HOOD("Шапка-жабка", "🐸", WardrobeSlot.HEAD),
    HEADPHONES("Наушники", "🎧", WardrobeSlot.HEAD),
    WIZARD("Шляпа мага", "🧙", WardrobeSlot.HEAD),
    CROWN("Корона", "👑", WardrobeSlot.HEAD),
    HALO("Нимб", "😇", WardrobeSlot.HEAD),
    BERET("Берет", "🎨", WardrobeSlot.HEAD),
    CAT_BAND("Ободок-ушки", "🐱", WardrobeSlot.HEAD),

    // FACE (5)
    SHADES("Крутые очки", "🕶️", WardrobeSlot.FACE),
    GLASSES("Умные очки", "👓", WardrobeSlot.FACE),
    CYBER_VISOR("Кибер-визор", "🤖", WardrobeSlot.FACE),
    STAR_CHEEKS("Звёзды на щёки", "⭐", WardrobeSlot.FACE),
    BLUSH_MASK("Маска-котик", "😷", WardrobeSlot.FACE),

    // TORSO (6)
    OVERALLS("Комбинезон", "👖", WardrobeSlot.TORSO),
    HOODIE("Худи", "🧥", WardrobeSlot.TORSO),
    KIMONO("Кимоно", "👘", WardrobeSlot.TORSO),
    DRESS("Платьице", "👗", WardrobeSlot.TORSO),
    IDOL_TOP("Костюм айдола", "🎶", WardrobeSlot.TORSO),
    SUIT("Смокинг", "🤵", WardrobeSlot.TORSO),

    // SHOES (4)
    SNEAKERS("Кроссовки", "👟", WardrobeSlot.SHOES),
    PAW_SLIPPERS("Тапочки-лапки", "🐾", WardrobeSlot.SHOES),
    BOOTS("Сапожки", "👢", WardrobeSlot.SHOES),
    STAR_HEELS("Звёздные туфли", "👠", WardrobeSlot.SHOES),

    // BACK (5)
    CAPE("Плащ героя", "🦸", WardrobeSlot.BACK),
    BACKPACK("Рюкзачок", "🎒", WardrobeSlot.BACK),
    ANGEL_WINGS_ACC("Крылышки", "🪽", WardrobeSlot.BACK),
    RIBBON_WINGS("Большой бант", "🎗️", WardrobeSlot.BACK),
    GUITAR_BACK("Электрогитара", "🎸", WardrobeSlot.BACK),

    // HAND (5)
    MIC("Микрофон", "🎤", WardrobeSlot.HAND),
    MAGIC_WAND("Жезл феи", "🪄", WardrobeSlot.HAND),
    PLUSH_BEAR("Плюшевый мишка", "🧸", WardrobeSlot.HAND),
    BOBA_TEA("Бабл-ти", "🧋", WardrobeSlot.HAND),
    UMBRELLA("Зонтик", "☂️", WardrobeSlot.HAND),

    // AURA (4)
    SAKURA_PETALS("Лепестки сакуры", "🌸", WardrobeSlot.AURA),
    MUSIC_NOTES("Музыкальные ноты", "🎵", WardrobeSlot.AURA),
    STARDUST("Звёздная пыль", "✨", WardrobeSlot.AURA),
    NEON_RING("Неоновое сияние", "💫", WardrobeSlot.AURA)
}

private data class StyleVariant(
    val suffix: String,
    val mainHex: Long,
    val accentHex: Long,
    val rarity: PetRarity,
    val basePrice: Int
)

data class GeneratedWardrobeItem(
    val id: String,
    val name: String,
    val emoji: String,
    val slot: WardrobeSlot,
    val shape: WardrobeShape,
    val mainHex: Long,
    val accentHex: Long,
    val rarity: PetRarity,
    val price: Int
)

object WardrobeGenerator {
    private val variants = listOf(
        StyleVariant("Нежный", 0xFF56CCF2L, 0xFFFFE6F2L, PetRarity.COMMON, 30),
        StyleVariant("Сакура", 0xFFFF6B9DL, 0xFFFFD166L, PetRarity.RARE, 60),
        StyleVariant("Кибер", 0xFF00E5FFL, 0xFFFF2E93L, PetRarity.EPIC, 105),
        StyleVariant("Люкс", 0xFFFFD166L, 0xFFFFFFFFL, PetRarity.LEGENDARY, 165)
    )

    // 38 уникальных фасонов × 4 стиля = 152 предмета одежды и аксессуаров в 7 категориях!
    val allItems: List<GeneratedWardrobeItem> = buildList {
        for (shape in WardrobeShape.entries) {
            variants.forEachIndexed { idx, v ->
                val priceBoost = (shape.ordinal % 5) * 8
                add(
                    GeneratedWardrobeItem(
                        id = "w_${shape.name.lowercase()}_$idx",
                        name = "${v.suffix} ${shape.baseName}",
                        emoji = shape.emoji,
                        slot = shape.slot,
                        shape = shape,
                        mainHex = v.mainHex,
                        accentHex = v.accentHex,
                        rarity = v.rarity,
                        price = v.basePrice + priceBoost
                    )
                )
            }
        }
    }

    fun getById(id: String?): GeneratedWardrobeItem? {
        if (id.isNullOrBlank()) return null
        return allItems.find { it.id == id }
    }
}

private val W_OUTLINE = Color(0xFF281938)

private fun DrawScope.wpx(
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

private fun DrawScope.wBox(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    fill: Color,
    outline: Color = W_OUTLINE,
    cell: Float,
    ox: Float,
    oy: Float
) {
    wpx(x, y, w, h, outline, cell, ox, oy)
    if (w > 2 && h > 2) {
        wpx(x + 1, y + 1, w - 2, h - 2, fill, cell, ox, oy)
    }
}

/**
 * Слой одежды и эффектов ЗА питомцем (сетка 64×64).
 */
fun DrawScope.drawWardrobeBack64(
    equipped: EquippedWardrobe,
    bodyY: Int,
    frame: Int,
    cell: Float,
    ox: Float,
    oy: Float
) {
    val backItem = WardrobeGenerator.getById(equipped.back)
    if (backItem != null) {
        val main = Color(backItem.mainHex)
        val accent = Color(backItem.accentHex)
        val sway = if (frame % 4 < 2) 0 else 1
        when (backItem.shape) {
            WardrobeShape.CAPE -> {
                wBox(14 - sway, bodyY + 1, 36 + sway * 2, 16, main, W_OUTLINE, cell, ox, oy)
                wpx(16 - sway, bodyY + 14, 32 + sway * 2, 2, accent, cell, ox, oy)
            }
            WardrobeShape.BACKPACK -> {
                wBox(13, bodyY + 2, 8, 12, main, W_OUTLINE, cell, ox, oy)
                wBox(43, bodyY + 2, 8, 12, main, W_OUTLINE, cell, ox, oy)
                wpx(15, bodyY + 4, 4, 3, accent, cell, ox, oy)
                wpx(45, bodyY + 4, 4, 3, accent, cell, ox, oy)
            }
            WardrobeShape.ANGEL_WINGS_ACC -> {
                val flap = if (frame % 2 == 0) 0 else -2
                wBox(6, bodyY + 1 + flap, 12, 10, main, W_OUTLINE, cell, ox, oy)
                wBox(8, bodyY + 9 + flap, 8, 5, accent, W_OUTLINE, cell, ox, oy)
                wBox(46, bodyY + 1 + flap, 12, 10, main, W_OUTLINE, cell, ox, oy)
                wBox(48, bodyY + 9 + flap, 8, 5, accent, W_OUTLINE, cell, ox, oy)
            }
            WardrobeShape.RIBBON_WINGS -> {
                wBox(9, bodyY + 3, 10, 8, main, W_OUTLINE, cell, ox, oy)
                wBox(45, bodyY + 3, 10, 8, main, W_OUTLINE, cell, ox, oy)
                wpx(12, bodyY + 5, 4, 4, accent, cell, ox, oy)
                wpx(48, bodyY + 5, 4, 4, accent, cell, ox, oy)
            }
            WardrobeShape.GUITAR_BACK -> {
                // Гриф гитары по диагонали за спиной
                for (i in 0..10) {
                    wpx(42 + i, bodyY - 8 + i, 3, 2, W_OUTLINE, cell, ox, oy)
                    wpx(43 + i, bodyY - 8 + i, 1, 2, accent, cell, ox, oy)
                }
                wBox(12, bodyY + 4, 11, 12, main, W_OUTLINE, cell, ox, oy)
                wpx(15, bodyY + 7, 5, 5, accent, cell, ox, oy)
            }
            else -> {}
        }
    }

    // Аура позади (для NEON_RING)
    val auraItem = WardrobeGenerator.getById(equipped.aura)
    if (auraItem?.shape == WardrobeShape.NEON_RING) {
        val main = Color(auraItem.mainHex)
        val accent = Color(auraItem.accentHex)
        val pulse = if (frame % 2 == 0) 0 else 1
        for (a in 0 until 16) {
            val ang = a * (Math.PI * 2 / 16) + frame * 0.25
            val rx = (32 + kotlin.math.cos(ang) * (23 + pulse)).toInt()
            val ry = (34 + kotlin.math.sin(ang) * (21 + pulse)).toInt()
            wpx(rx, ry, 2, 2, if (a % 2 == 0) main else accent, cell, ox, oy)
        }
    }
}

/**
 * Слой одежды и аксессуаров НАД телом питомца (сетка 64×64).
 */
fun DrawScope.drawWardrobeFront64(
    equipped: EquippedWardrobe,
    hx: Int,
    headY: Int,
    eyeY: Int,
    bodyY: Int,
    headW: Int,
    frame: Int,
    cell: Float,
    ox: Float,
    oy: Float
) {
    val cx = hx + headW / 2

    // 1. ТОРС (ОДЕЖДА)
    WardrobeGenerator.getById(equipped.torso)?.let { torso ->
        val main = Color(torso.mainHex)
        val accent = Color(torso.accentHex)
        when (torso.shape) {
            WardrobeShape.OVERALLS -> {
                // Милый комбинезон как на референсе с лямочками!
                wBox(cx - 9, bodyY + 3, 18, 12, main, W_OUTLINE, cell, ox, oy)
                wpx(cx - 7, bodyY, 3, 4, main, cell, ox, oy)
                wpx(cx + 4, bodyY, 3, 4, main, cell, ox, oy)
                wpx(cx - 6, bodyY + 4, 2, 2, accent, cell, ox, oy)
                wpx(cx + 4, bodyY + 4, 2, 2, accent, cell, ox, oy)
                wBox(cx - 4, bodyY + 8, 8, 4, main.copy(alpha = 0.85f), W_OUTLINE, cell, ox, oy)
            }
            WardrobeShape.HOODIE -> {
                wBox(cx - 10, bodyY + 1, 20, 14, main, W_OUTLINE, cell, ox, oy)
                wpx(cx - 4, bodyY + 2, 2, 5, accent, cell, ox, oy)
                wpx(cx + 2, bodyY + 2, 2, 5, accent, cell, ox, oy)
                wBox(cx - 5, bodyY + 9, 10, 4, accent, W_OUTLINE, cell, ox, oy)
            }
            WardrobeShape.KIMONO -> {
                wBox(cx - 11, bodyY + 1, 22, 15, main, W_OUTLINE, cell, ox, oy)
                wpx(cx - 3, bodyY + 1, 6, 4, Color.White, cell, ox, oy)
                wpx(cx - 10, bodyY + 7, 20, 4, accent, cell, ox, oy)
                wpx(cx - 2, bodyY + 6, 4, 6, accent, cell, ox, oy)
            }
            WardrobeShape.DRESS -> {
                wBox(cx - 9, bodyY + 1, 18, 7, main, W_OUTLINE, cell, ox, oy)
                wBox(cx - 13, bodyY + 7, 26, 9, main, W_OUTLINE, cell, ox, oy)
                wpx(cx - 12, bodyY + 13, 24, 2, accent, cell, ox, oy)
                wpx(cx - 2, bodyY + 3, 4, 3, accent, cell, ox, oy)
            }
            WardrobeShape.IDOL_TOP -> {
                wBox(cx - 9, bodyY + 1, 18, 8, Color(0xFFE8EEF5), W_OUTLINE, cell, ox, oy)
                wpx(cx - 1, bodyY + 2, 2, 7, main, cell, ox, oy) // галстук
                wBox(cx - 11, bodyY + 8, 22, 7, main, W_OUTLINE, cell, ox, oy)
                wpx(cx - 10, bodyY + 13, 20, 1, accent, cell, ox, oy)
            }
            WardrobeShape.SUIT -> {
                wBox(cx - 10, bodyY + 1, 20, 14, Color(0xFF22223B), W_OUTLINE, cell, ox, oy)
                wpx(cx - 3, bodyY + 1, 6, 8, Color.White, cell, ox, oy)
                wpx(cx - 2, bodyY + 2, 4, 2, main, cell, ox, oy) // бабочка
                wpx(cx + 4, bodyY + 5, 3, 2, accent, cell, ox, oy)
            }
            else -> {}
        }
    }

    // 2. ОБУВЬ
    WardrobeGenerator.getById(equipped.shoes)?.let { shoes ->
        val main = Color(shoes.mainHex)
        val accent = Color(shoes.accentHex)
        val footY = bodyY + 14
        when (shoes.shape) {
            WardrobeShape.SNEAKERS -> {
                wBox(cx - 9, footY, 7, 5, main, W_OUTLINE, cell, ox, oy)
                wpx(cx - 8, footY + 3, 5, 1, Color.White, cell, ox, oy)
                wBox(cx + 2, footY, 7, 5, main, W_OUTLINE, cell, ox, oy)
                wpx(cx + 3, footY + 3, 5, 1, Color.White, cell, ox, oy)
            }
            WardrobeShape.PAW_SLIPPERS -> {
                wBox(cx - 10, footY, 8, 5, main, W_OUTLINE, cell, ox, oy)
                wpx(cx - 8, footY + 1, 4, 2, accent, cell, ox, oy)
                wBox(cx + 2, footY, 8, 5, main, W_OUTLINE, cell, ox, oy)
                wpx(cx + 4, footY + 1, 4, 2, accent, cell, ox, oy)
            }
            WardrobeShape.BOOTS -> {
                wBox(cx - 9, footY - 2, 7, 7, main, W_OUTLINE, cell, ox, oy)
                wpx(cx - 8, footY - 1, 5, 2, accent, cell, ox, oy)
                wBox(cx + 2, footY - 2, 7, 7, main, W_OUTLINE, cell, ox, oy)
                wpx(cx + 3, footY - 1, 5, 2, accent, cell, ox, oy)
            }
            WardrobeShape.STAR_HEELS -> {
                wBox(cx - 9, footY, 7, 5, main, W_OUTLINE, cell, ox, oy)
                wpx(cx - 7, footY + 1, 3, 2, accent, cell, ox, oy)
                wBox(cx + 2, footY, 7, 5, main, W_OUTLINE, cell, ox, oy)
                wpx(cx + 4, footY + 1, 3, 2, accent, cell, ox, oy)
            }
            else -> {}
        }
    }

    // 3. ЛИЦО (ОЧКИ, ВИЗОР, РУМЯНЕЦ)
    WardrobeGenerator.getById(equipped.face)?.let { face ->
        val main = Color(face.mainHex)
        val accent = Color(face.accentHex)
        when (face.shape) {
            WardrobeShape.SHADES -> {
                wpx(hx + 3, eyeY, headW - 6, 2, W_OUTLINE, cell, ox, oy)
                wBox(hx + 4, eyeY, 10, 7, main, W_OUTLINE, cell, ox, oy)
                wBox(hx + headW - 14, eyeY, 10, 7, main, W_OUTLINE, cell, ox, oy)
                wpx(hx + 6, eyeY + 2, 4, 1, accent, cell, ox, oy)
                wpx(hx + headW - 12, eyeY + 2, 4, 1, accent, cell, ox, oy)
            }
            WardrobeShape.GLASSES -> {
                wBox(hx + 4, eyeY - 1, 10, 8, main, W_OUTLINE, cell, ox, oy)
                wpx(hx + 6, eyeY + 1, 6, 4, Color(0x88FFFFFF), cell, ox, oy)
                wBox(hx + headW - 14, eyeY - 1, 10, 8, main, W_OUTLINE, cell, ox, oy)
                wpx(hx + headW - 12, eyeY + 1, 6, 4, Color(0x88FFFFFF), cell, ox, oy)
                wpx(cx - 2, eyeY + 2, 4, 2, main, cell, ox, oy)
            }
            WardrobeShape.CYBER_VISOR -> {
                wBox(hx + 3, eyeY, headW - 6, 6, Color(0xFF141834), W_OUTLINE, cell, ox, oy)
                wpx(hx + 5, eyeY + 2, headW - 10, 2, main, cell, ox, oy)
                wpx(hx + 7, eyeY + 2, 5, 1, accent, cell, ox, oy)
            }
            WardrobeShape.STAR_CHEEKS -> {
                wpx(hx + 4, eyeY + 7, 3, 3, main, cell, ox, oy)
                wpx(hx + 5, eyeY + 8, 1, 1, accent, cell, ox, oy)
                wpx(hx + headW - 7, eyeY + 7, 3, 3, main, cell, ox, oy)
                wpx(hx + headW - 6, eyeY + 8, 1, 1, accent, cell, ox, oy)
            }
            WardrobeShape.BLUSH_MASK -> {
                wBox(cx - 8, eyeY + 6, 16, 7, Color.White, W_OUTLINE, cell, ox, oy)
                wpx(cx - 3, eyeY + 8, 6, 2, main, cell, ox, oy)
                wpx(cx - 1, eyeY + 10, 2, 1, accent, cell, ox, oy)
            }
            else -> {}
        }
    }

    // 4. ГОЛОВА (ШАПКИ, КОРОНЫ, БАНТИКИ, НАУШНИКИ)
    WardrobeGenerator.getById(equipped.head)?.let { hat ->
        val main = Color(hat.mainHex)
        val accent = Color(hat.accentHex)
        when (hat.shape) {
            WardrobeShape.BOW -> {
                wBox(cx + 2, headY - 5, 7, 6, main, W_OUTLINE, cell, ox, oy)
                wBox(cx + 9, headY - 5, 7, 6, main, W_OUTLINE, cell, ox, oy)
                wBox(cx + 7, headY - 3, 4, 4, accent, W_OUTLINE, cell, ox, oy)
            }
            WardrobeShape.CAP -> {
                wBox(cx - 11, headY - 6, 22, 8, main, W_OUTLINE, cell, ox, oy)
                wBox(cx - 5, headY - 4, 10, 4, accent, W_OUTLINE, cell, ox, oy)
                wBox(cx + 4, headY, 13, 3, main.copy(alpha = 0.9f), W_OUTLINE, cell, ox, oy)
            }
            WardrobeShape.FROG_HOOD -> {
                wBox(cx - 12, headY - 5, 24, 7, main, W_OUTLINE, cell, ox, oy)
                wBox(cx - 10, headY - 10, 7, 6, main, W_OUTLINE, cell, ox, oy)
                wBox(cx + 3, headY - 10, 7, 6, main, W_OUTLINE, cell, ox, oy)
                wpx(cx - 8, headY - 8, 3, 3, Color.White, cell, ox, oy)
                wpx(cx - 7, headY - 7, 2, 2, W_OUTLINE, cell, ox, oy)
                wpx(cx + 5, headY - 8, 3, 3, Color.White, cell, ox, oy)
                wpx(cx + 6, headY - 7, 2, 2, W_OUTLINE, cell, ox, oy)
            }
            WardrobeShape.HEADPHONES -> {
                wBox(hx + 2, headY - 4, headW - 4, 4, W_OUTLINE, W_OUTLINE, cell, ox, oy)
                wpx(hx + 4, headY - 3, headW - 8, 2, main, cell, ox, oy)
                wBox(hx - 3, eyeY - 2, 5, 11, main, W_OUTLINE, cell, ox, oy)
                wBox(hx + headW - 2, eyeY - 2, 5, 11, main, W_OUTLINE, cell, ox, oy)
                wpx(hx - 2, eyeY + 1, 2, 5, accent, cell, ox, oy)
                wpx(hx + headW, eyeY + 1, 2, 5, accent, cell, ox, oy)
            }
            WardrobeShape.WIZARD -> {
                wBox(cx - 15, headY - 2, 30, 4, main, W_OUTLINE, cell, ox, oy)
                wBox(cx - 10, headY - 8, 20, 7, main, W_OUTLINE, cell, ox, oy)
                wBox(cx - 6, headY - 14, 12, 7, main, W_OUTLINE, cell, ox, oy)
                wBox(cx - 2, headY - 18, 6, 5, main, W_OUTLINE, cell, ox, oy)
                wpx(cx - 9, headY - 4, 18, 2, accent, cell, ox, oy)
            }
            WardrobeShape.CROWN -> {
                wBox(cx - 9, headY - 7, 18, 8, main, W_OUTLINE, cell, ox, oy)
                wBox(cx - 9, headY - 11, 4, 5, main, W_OUTLINE, cell, ox, oy)
                wBox(cx - 2, headY - 12, 4, 6, main, W_OUTLINE, cell, ox, oy)
                wBox(cx + 5, headY - 11, 4, 5, main, W_OUTLINE, cell, ox, oy)
                wpx(cx - 1, headY - 5, 2, 3, accent, cell, ox, oy)
                wpx(cx - 7, headY - 4, 2, 2, Color(0xFF00E5FF), cell, ox, oy)
                wpx(cx + 5, headY - 4, 2, 2, Color(0xFF00E5FF), cell, ox, oy)
            }
            WardrobeShape.HALO -> {
                val lift = if (frame % 2 == 0) 0 else -1
                wBox(cx - 10, headY - 11 + lift, 20, 4, main, W_OUTLINE, cell, ox, oy)
                wpx(cx - 7, headY - 10 + lift, 14, 2, accent, cell, ox, oy)
            }
            WardrobeShape.BERET -> {
                wBox(cx - 11, headY - 6, 22, 7, main, W_OUTLINE, cell, ox, oy)
                wBox(cx - 7, headY - 8, 16, 4, main, W_OUTLINE, cell, ox, oy)
                wpx(cx, headY - 10, 2, 3, W_OUTLINE, cell, ox, oy)
                wpx(cx - 8, headY - 2, 16, 2, accent, cell, ox, oy)
            }
            WardrobeShape.CAT_BAND -> {
                wpx(hx + 4, headY - 2, headW - 8, 3, W_OUTLINE, cell, ox, oy)
                wBox(hx + 3, headY - 8, 7, 7, main, W_OUTLINE, cell, ox, oy)
                wBox(hx + headW - 10, headY - 8, 7, 7, main, W_OUTLINE, cell, ox, oy)
                wpx(hx + 5, headY - 6, 3, 3, accent, cell, ox, oy)
                wpx(hx + headW - 8, headY - 6, 3, 3, accent, cell, ox, oy)
            }
            else -> {}
        }

        if (hat.rarity == PetRarity.LEGENDARY || hat.rarity == PetRarity.EPIC) {
            val spCol = if (hat.rarity == PetRarity.LEGENDARY) Color(0xFFFFF59D) else Color(0xFF80D8FF)
            if (frame % 4 == 0) wpx(hx + 1, headY - 10, 2, 2, spCol, cell, ox, oy)
            if (frame % 4 == 2) wpx(hx + headW - 3, headY - 12, 2, 2, spCol, cell, ox, oy)
        }
    }

    // 5. В ЛАПКАХ (ПРЕДМЕТЫ)
    WardrobeGenerator.getById(equipped.hand)?.let { hand ->
        val main = Color(hand.mainHex)
        val accent = Color(hand.accentHex)
        val rx = cx + 11
        val ry = bodyY + 2
        when (hand.shape) {
            WardrobeShape.MIC -> {
                wBox(rx, ry + 3, 3, 8, W_OUTLINE, W_OUTLINE, cell, ox, oy)
                wpx(rx + 1, ry + 4, 1, 5, main, cell, ox, oy)
                wBox(rx - 1, ry - 2, 5, 6, accent, W_OUTLINE, cell, ox, oy)
            }
            WardrobeShape.MAGIC_WAND -> {
                wBox(rx, ry + 1, 3, 10, accent, W_OUTLINE, cell, ox, oy)
                wBox(rx - 2, ry - 4, 7, 6, main, W_OUTLINE, cell, ox, oy)
                wpx(rx, ry - 2, 3, 2, Color.White, cell, ox, oy)
            }
            WardrobeShape.PLUSH_BEAR -> {
                wBox(rx - 1, ry + 1, 8, 9, main, W_OUTLINE, cell, ox, oy)
                wBox(rx - 1, ry - 1, 3, 3, main, W_OUTLINE, cell, ox, oy)
                wBox(rx + 4, ry - 1, 3, 3, main, W_OUTLINE, cell, ox, oy)
                wpx(rx + 1, ry + 3, 4, 3, accent, cell, ox, oy)
            }
            WardrobeShape.BOBA_TEA -> {
                wBox(rx, ry + 1, 6, 8, accent, W_OUTLINE, cell, ox, oy)
                wpx(rx + 1, ry + 4, 4, 4, main, cell, ox, oy)
                wpx(rx + 2, ry - 3, 2, 5, W_OUTLINE, cell, ox, oy)
                wpx(rx + 1, ry + 6, 1, 1, W_OUTLINE, cell, ox, oy)
                wpx(rx + 3, ry + 6, 1, 1, W_OUTLINE, cell, ox, oy)
            }
            WardrobeShape.UMBRELLA -> {
                wBox(rx + 1, ry - 2, 3, 12, W_OUTLINE, W_OUTLINE, cell, ox, oy)
                wBox(rx - 5, ry - 7, 15, 6, main, W_OUTLINE, cell, ox, oy)
                wpx(rx - 3, ry - 5, 11, 2, accent, cell, ox, oy)
            }
            else -> {}
        }
    }

    // 6. АУРА / ПЛАВАЮЩИЕ ЧАСТИЦЫ СПЕРЕДИ
    WardrobeGenerator.getById(equipped.aura)?.let { aura ->
        val main = Color(aura.mainHex)
        val accent = Color(aura.accentHex)
        val t = frame % 8
        when (aura.shape) {
            WardrobeShape.SAKURA_PETALS -> {
                wpx(8, 12 + t, 3, 2, Color(0xFFFF85B3), cell, ox, oy)
                wpx(52, 18 + ((t + 4) % 8), 3, 2, Color(0xFFFFB8D9), cell, ox, oy)
                wpx(14, 42 + (t % 4), 2, 2, Color(0xFFFF4785), cell, ox, oy)
            }
            WardrobeShape.MUSIC_NOTES -> {
                val bob = if (frame % 2 == 0) 0 else -2
                wpx(9, 16 + bob, 2, 5, main, cell, ox, oy)
                wpx(9, 15 + bob, 4, 2, main, cell, ox, oy)
                wpx(52, 20 - bob, 2, 5, accent, cell, ox, oy)
                wpx(52, 19 - bob, 4, 2, accent, cell, ox, oy)
            }
            WardrobeShape.STARDUST -> {
                if (frame % 2 == 0) {
                    wpx(10, 14, 3, 1, accent, cell, ox, oy); wpx(11, 13, 1, 3, accent, cell, ox, oy)
                    wpx(51, 36, 3, 1, main, cell, ox, oy); wpx(52, 35, 1, 3, main, cell, ox, oy)
                } else {
                    wpx(50, 14, 3, 1, accent, cell, ox, oy); wpx(51, 13, 1, 3, accent, cell, ox, oy)
                    wpx(11, 38, 3, 1, main, cell, ox, oy); wpx(12, 37, 1, 3, main, cell, ox, oy)
                }
            }
            else -> {}
        }
    }
}
