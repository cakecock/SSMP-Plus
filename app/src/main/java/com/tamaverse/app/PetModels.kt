package com.tamaverse.app

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import java.util.UUID
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class PetStage(val title: String, val minAgeMinutes: Int) {
    EGG("Яйцо", 0),
    BABY("Малыш", 2),
    CHILD("Ребёнок", 25),
    TEEN("Подросток", 90),
    ADULT("Взрослый", 240)
}

enum class PetRarity(val title: String, val colorHex: Long) {
    COMMON("Обычный", 0xFF7BE495L),
    RARE("Редкий", 0xFF56CCF2L),
    EPIC("Эпический", 0xFFC77DFFL),
    LEGENDARY("Легенда", 0xFFFFD166L)
}

enum class PetMood(val title: String, val emoji: String) {
    ECSTATIC("Восторг", "✨"),
    IN_LOVE("Влюблён", "💖"),
    HAPPY("Радостный", "😊"),
    PLAYFUL("Игривый", "🎵"),
    SHY("Стесняется", "😳"),
    BORED("Скучает", "😐"),
    GRUMPY("Обижен", "😤"),
    SLEEPY("Сонный", "😴"),
    SICK("Болеет", "🤒"),
    DECEASED("Ангелочек", "👼")
}

// 16 принципиально разных силуэтов питомцев
enum class PetSilhouette {
    CLASSIC_CHIBI,
    BUNNY_PLUSH,
    CAPYBARA_ROUND,
    BIRD_OWL,
    DRAGON_WINGED,
    AXOLOTL_AQUA,
    SLIME_JELLY,
    TURTLE_SHELL,
    OCTO_TENTACLES,
    WHALE_FIN,
    GHOST_FLOAT,
    MECHA_BOT,
    BEE_BUG,
    SNAKE_COIL,
    FOX_SLENDER,
    HERO_CHIBI
}

// Плавные формы головы в стиле Touma Pet / Tamagotchi Meets
enum class HeadShape {
    ROUND,       // Круглая милая мордочка
    OVAL,        // Вытянутая как у зайки
    STAR_FIVE,   // 5-конечная звезда-голова!
    SUN_SPIKY,   // Пушистая лучистая звезда-солнышко!
    PEAR,        // Грушевидная / капля
    CLOUD,       // Волнистое облачко
    CHIBI_HUMAN, // Чиби-голова с мягкой линией подбородка
    DIAMOND,     // Лисья/драконья мордочка
    WIDE,        // Широкая амфибия / аксолотль / лягушка
    SQUARE_TV    // Скруглённый монитор ретро-дроида
}

enum class EarType {
    NONE, CAT, BUNNY, BEAR, PUPPY, FOX, DRAGON_HORNS, UNICORN_HORN,
    FROG_EYES, ANTENNA, BAT_EARS, LEAF_SPROUT, ANTLERS, OWL_TUFTS,
    FIN_EARS, BUG_FEELERS, HEART_ANTENNA, MONKEY_EARS
}

enum class HairStyle {
    NONE, TWIN_TAILS, JESTER_HOOD, HIGH_PONYTAIL, KABUTO_HELM,
    SHINOBI_COWL, TV_SCREEN, MANDARIN_TOP, FLUFF_TUFT, LION_MANE,
    WATER_SPOUT, FLOWER_CROWN, CREST_FIN, CHEF_HAT, SWOOP_HAIR
}

enum class BackFeature {
    NONE, ANGEL_WINGS, BAT_WINGS, FAIRY_WINGS, FLUFFY_TAIL, CAT_TAIL,
    DINO_SPIKES, AXO_GILLS, AURA_RING, TURTLE_CARAPACE, KATANA_BACK, JETPACK
}

enum class FaceStyle { KAWAII, BEAK, SNOUT, FANG, VISOR, WHISKERS, STAR_BLUSH, CAT_W }
enum class MarkingType { BELLY, STRIPES, SPOTS, HEART_CHEST, STAR_FOREHEAD, CHEEK_MARKS, TUXEDO, PANDA_PATCH }
enum class EyeStyle { ANIME, SLEEPY, STARRY, BUTTON, CYBER, SPARKLE_GEM }

enum class HeroSpecial {
    NONE,
    CYBER_DIVA,    // Стиль Хацуне Мику
    GOTH_BUNNY,    // Стиль Куроми
    COSMIC_WITCH,  // Космическая волшебница
    SAMURAI_CAT,   // Кот-самурай
    NINJA_FOX,     // Лис-ниндзя
    RETRO_BOT,     // Робот с живым экранчиком
    CHEF_BEAR,     // Шеф-повар с колпаком (из референса Touma Pet!)
    STAR_ALIEN     // Космическая звёздочка (из референса Touma Pet!)
}

enum class PetPalette(
    val title: String,
    val emoji: String,
    val price: Int,
    val primary: Long,
    val light: Long,
    val accent: Long,
    val outline: Long
) {
    SAKURA("Сакура", "🌸", 0, 0xFFFF85B3L, 0xFFFFD9E8L, 0xFFFF4785L, 0xFF33183FL),
    MINT("Мятный", "🍃", 0, 0xFF5CE1B8L, 0xFFCFFCEEL, 0xFF18A979L, 0xFF143632L),
    SKY("Небесный", "☁️", 40, 0xFF64B5F6L, 0xFFD4EEFFL, 0xFF2B7DE9L, 0xFF17274DL),
    SUNSET("Персик", "🍑", 40, 0xFFFF9A62L, 0xFFFFE2CCL, 0xFFFF5252L, 0xFF421C29L),
    SNOW("Снежок", "❄️", 45, 0xFFEAF4FFL, 0xFFFFFFFFL, 0xFF74B9FFL, 0xFF25324FL),
    CHOCO("Шоколад", "🍫", 45, 0xFFB07D62L, 0xFFF3D5B5L, 0xFF7F4F24L, 0xFF321E14L),
    LAVENDER("Лаванда", "💜", 55, 0xFFC8A2FFL, 0xFFF0E6FFL, 0xFF8E44ADL, 0xFF2B1845L),
    BERRY("Ягодный", "🍓", 60, 0xFFE55999L, 0xFFFFC9E2L, 0xFF9B51E0L, 0xFF331330L),
    FLAME("Огненный", "🔥", 75, 0xFFFF5E57L, 0xFFFFD8A8L, 0xFFFFA801L, 0xFF451414L),
    GALAXY("Космо", "🌌", 90, 0xFF7B61FFL, 0xFFDCD3FFL, 0xFFFF66C4L, 0xFF1A103CL),
    CYBER("Кибер-Неон", "⚡", 100, 0xFF00E5FFL, 0xFFC2FAFFL, 0xFFFF2E93L, 0xFF12183BL),
    GOLD("Золотой", "👑", 120, 0xFFFFCC42L, 0xFFFFF3C2L, 0xFFFF851BL, 0xFF422713L);

    companion object {
        fun random(): PetPalette = entries[Random.nextInt(entries.size)]
        fun byNameOrDefault(name: String?, fallback: PetPalette): PetPalette =
            entries.find { it.name == name } ?: fallback
    }
}

data class PetDefinition(
    val id: Int,
    val name: String,
    val rarity: PetRarity,
    val isStarter: Boolean,
    val personality: String,
    val defaultPalette: PetPalette,
    val silhouette: PetSilhouette,
    val head: HeadShape,
    val ears: EarType,
    val hair: HairStyle = HairStyle.NONE,
    val back: BackFeature = BackFeature.NONE,
    val face: FaceStyle = FaceStyle.KAWAII,
    val marking: MarkingType = MarkingType.BELLY,
    val eyes: EyeStyle = EyeStyle.ANIME,
    val heroSpecial: HeroSpecial = HeroSpecial.NONE,
    val favoriteFoodId: String = "onigiri",
    val pettingReaction: String = "Мурр~ Как приятно! ♥"
)

object PetCatalog {
    private val personalities = listOf(
        "Игривый", "Ласковый", "Сонный", "Любопытный",
        "Храбрый", "Сладкоежка", "Мечтатель", "Умник",
        "Озорной", "Добрый", "Звёздный", "Артистичный"
    )

    private val affectionPhrases = listOf(
        "Мурр~ Так тепло! ♥",
        "Ещё погладь, пожалуйста! ★",
        "Так уютно и приятно~ ♥",
        "Хвостик виляет от счастья!",
        "Как же я тебя люблю! 💖",
        "Самые нежные ручки! ✨",
        "Довольный жмурюсь~ ♥",
        "Твои объятия — лучшие! ★"
    )

    private data class PetBlueprint(
        val name: String,
        val rarity: PetRarity,
        val silhouette: PetSilhouette,
        val head: HeadShape,
        val ears: EarType,
        val hair: HairStyle,
        val back: BackFeature,
        val face: FaceStyle,
        val marking: MarkingType,
        val eyes: EyeStyle,
        val palette: PetPalette,
        val hero: HeroSpecial = HeroSpecial.NONE,
        val favFood: String = "berry",
        val reaction: String = "Мурр~ Так приятно! ♥"
    )

    private val specialUnlockables: List<PetBlueprint> = listOf(
        // 15..22: ОСОБЕННЫЕ ГЕРОИ И ПЕРСОНАЖИ ИЗ РЕФЕРЕНСОВ TOUMA PET
        PetBlueprint("Микуня (Кибер-Дива)", PetRarity.LEGENDARY, PetSilhouette.HERO_CHIBI, HeadShape.CHIBI_HUMAN, EarType.NONE, HairStyle.TWIN_TAILS, BackFeature.AURA_RING, FaceStyle.STAR_BLUSH, MarkingType.TUXEDO, EyeStyle.SPARKLE_GEM, PetPalette.CYBER, HeroSpecial.CYBER_DIVA, "icecream", "Ля-ля-ля~ ♥ Ещё погладь! ♪"),
        PetBlueprint("Куро-Зайка", PetRarity.EPIC, PetSilhouette.HERO_CHIBI, HeadShape.ROUND, EarType.BUNNY, HairStyle.JESTER_HOOD, BackFeature.FLUFFY_TAIL, FaceStyle.CAT_W, MarkingType.HEART_CHEST, EyeStyle.ANIME, PetPalette.BERRY, HeroSpecial.GOTH_BUNNY, "cake", "Хмф! Ну ладно, мне приятно~ ♥"),
        PetBlueprint("Луна-Магичка", PetRarity.LEGENDARY, PetSilhouette.HERO_CHIBI, HeadShape.CHIBI_HUMAN, EarType.NONE, HairStyle.HIGH_PONYTAIL, BackFeature.FAIRY_WINGS, FaceStyle.STAR_BLUSH, MarkingType.STAR_FOREHEAD, EyeStyle.STARRY, PetPalette.GALAXY, HeroSpecial.COSMIC_WITCH, "donut", "Тёплая магия ладошек! ✨"),
        PetBlueprint("Кот-Самурай", PetRarity.EPIC, PetSilhouette.CLASSIC_CHIBI, HeadShape.ROUND, EarType.CAT, HairStyle.KABUTO_HELM, BackFeature.KATANA_BACK, FaceStyle.WHISKERS, MarkingType.TUXEDO, EyeStyle.ANIME, PetPalette.FLAME, HeroSpecial.SAMURAI_CAT, "sushi", "Гладить воина разрешается! ♥"),
        PetBlueprint("Ниндзя-Лис", PetRarity.EPIC, PetSilhouette.FOX_SLENDER, HeadShape.DIAMOND, EarType.FOX, HairStyle.SHINOBI_COWL, BackFeature.FLUFFY_TAIL, FaceStyle.CAT_W, MarkingType.CHEEK_MARKS, EyeStyle.ANIME, PetPalette.SUNSET, HeroSpecial.NINJA_FOX, "onigiri", "Быстро и нежно~ ★"),
        PetBlueprint("Ретро-Дроид", PetRarity.RARE, PetSilhouette.MECHA_BOT, HeadShape.SQUARE_TV, EarType.ANTENNA, HairStyle.TV_SCREEN, BackFeature.JETPACK, FaceStyle.VISOR, MarkingType.HEART_CHEST, EyeStyle.CYBER, PetPalette.SKY, HeroSpecial.RETRO_BOT, "burger", "Датчик нежности: 100%! ♥"),
        PetBlueprint("Шеф-Мишка", PetRarity.RARE, PetSilhouette.CAPYBARA_ROUND, HeadShape.ROUND, EarType.BEAR, HairStyle.CHEF_HAT, BackFeature.NONE, FaceStyle.SNOUT, MarkingType.TUXEDO, EyeStyle.ANIME, PetPalette.SUNSET, HeroSpecial.CHEF_BEAR, "cake", "Одобряю такие обнимашки! 🍰"),
        PetBlueprint("Космо-Звёздочка", PetRarity.EPIC, PetSilhouette.CLASSIC_CHIBI, HeadShape.STAR_FIVE, EarType.NONE, HairStyle.NONE, BackFeature.AURA_RING, FaceStyle.STAR_BLUSH, MarkingType.STAR_FOREHEAD, EyeStyle.SPARKLE_GEM, PetPalette.LAVENDER, HeroSpecial.STAR_ALIEN, "icecream", "Сияю от твоей заботы! ✨"),

        // 23..56: УНИКАЛЬНЫЕ ЗВЕРИ, СУЩЕСТВА И ПЕРСОНАЖИ С РАЗНЫМИ СИЛУЭТАМИ И ФОРМАМИ ГОЛОВЫ
        PetBlueprint("Солнышко-Пуф", PetRarity.RARE, PetSilhouette.SLIME_JELLY, HeadShape.SUN_SPIKY, EarType.NONE, HairStyle.FLUFF_TUFT, BackFeature.AURA_RING, FaceStyle.KAWAII, MarkingType.CHEEK_MARKS, EyeStyle.ANIME, PetPalette.GOLD, favFood = "donut", reaction = "Греюсь в твоих ладошках! ☀️"),
        PetBlueprint("Фея-Сердечко", PetRarity.RARE, PetSilhouette.HERO_CHIBI, HeadShape.CHIBI_HUMAN, EarType.HEART_ANTENNA, HairStyle.SWOOP_HAIR, BackFeature.FAIRY_WINGS, FaceStyle.STAR_BLUSH, MarkingType.HEART_CHEST, EyeStyle.SPARKLE_GEM, PetPalette.FLAME, favFood = "berry", reaction = "Крылышки дрожат от радости! 💖"),
        PetBlueprint("Ниндзя-Тень", PetRarity.RARE, PetSilhouette.HERO_CHIBI, HeadShape.CHIBI_HUMAN, EarType.NONE, HairStyle.SHINOBI_COWL, BackFeature.KATANA_BACK, FaceStyle.KAWAII, MarkingType.TUXEDO, EyeStyle.ANIME, PetPalette.LAVENDER, favFood = "onigiri", reaction = "Скрытно мурчу! ♥"),
        PetBlueprint("Стиляга-Чиби", PetRarity.COMMON, PetSilhouette.HERO_CHIBI, HeadShape.CHIBI_HUMAN, EarType.NONE, HairStyle.SWOOP_HAIR, BackFeature.NONE, FaceStyle.CAT_W, MarkingType.TUXEDO, EyeStyle.ANIME, PetPalette.SKY, favFood = "burger", reaction = "Ух ты, кайф! ★"),
        PetBlueprint("Обезьянка-Чичи", PetRarity.COMMON, PetSilhouette.CLASSIC_CHIBI, HeadShape.ROUND, EarType.MONKEY_EARS, HairStyle.FLUFF_TUFT, BackFeature.CAT_TAIL, FaceStyle.SNOUT, MarkingType.BELLY, EyeStyle.ANIME, PetPalette.CHOCO, favFood = "berry", reaction = "У-у-а! Давай ещё погладь!"),
        PetBlueprint("Гоблин-Тимоша", PetRarity.RARE, PetSilhouette.HERO_CHIBI, HeadShape.PEAR, EarType.BAT_EARS, HairStyle.FLUFF_TUFT, BackFeature.NONE, FaceStyle.FANG, MarkingType.TUXEDO, EyeStyle.ANIME, PetPalette.SKY, favFood = "burger", reaction = "Хе-хе, вообще-то мне нравится! ♥"),
        PetBlueprint("Капибара", PetRarity.RARE, PetSilhouette.CAPYBARA_ROUND, HeadShape.WIDE, EarType.BEAR, HairStyle.MANDARIN_TOP, BackFeature.NONE, FaceStyle.SNOUT, MarkingType.BELLY, EyeStyle.SLEEPY, PetPalette.CHOCO, favFood = "berry", reaction = "Полный дзен и блаженство 🍊"),
        PetBlueprint("Корги", PetRarity.RARE, PetSilhouette.CLASSIC_CHIBI, HeadShape.ROUND, EarType.FOX, HairStyle.FLUFF_TUFT, BackFeature.FLUFFY_TAIL, FaceStyle.SNOUT, MarkingType.TUXEDO, EyeStyle.ANIME, PetPalette.SUNSET, favFood = "burger", reaction = "Тяф-тяф! Хвостик крутится!"),
        PetBlueprint("Красная Панда", PetRarity.RARE, PetSilhouette.FOX_SLENDER, HeadShape.ROUND, EarType.BEAR, HairStyle.FLUFF_TUFT, BackFeature.FLUFFY_TAIL, FaceStyle.WHISKERS, MarkingType.PANDA_PATCH, EyeStyle.BUTTON, PetPalette.FLAME, favFood = "berry", reaction = "Тёплые обнимашки! ♥"),
        PetBlueprint("Панда", PetRarity.COMMON, PetSilhouette.CAPYBARA_ROUND, HeadShape.ROUND, EarType.BEAR, HairStyle.NONE, BackFeature.NONE, FaceStyle.SNOUT, MarkingType.PANDA_PATCH, EyeStyle.BUTTON, PetPalette.SNOW, favFood = "onigiri", reaction = "Ур-ур! Так славно!"),
        PetBlueprint("Совёнок", PetRarity.COMMON, PetSilhouette.BIRD_OWL, HeadShape.OVAL, EarType.OWL_TUFTS, HairStyle.NONE, BackFeature.ANGEL_WINGS, FaceStyle.BEAK, MarkingType.SPOTS, EyeStyle.ANIME, PetPalette.CHOCO, favFood = "berry", reaction = "Угу-угу~ Перья пушатся!"),
        PetBlueprint("Енот", PetRarity.COMMON, PetSilhouette.FOX_SLENDER, HeadShape.DIAMOND, EarType.FOX, HairStyle.FLUFF_TUFT, BackFeature.FLUFFY_TAIL, FaceStyle.SNOUT, MarkingType.PANDA_PATCH, EyeStyle.BUTTON, PetPalette.SKY, favFood = "donut", reaction = "Лапки довольны! ♥"),
        PetBlueprint("Оленёнок", PetRarity.RARE, PetSilhouette.CLASSIC_CHIBI, HeadShape.OVAL, EarType.ANTLERS, HairStyle.FLOWER_CROWN, BackFeature.FLUFFY_TAIL, FaceStyle.SNOUT, MarkingType.SPOTS, EyeStyle.ANIME, PetPalette.SUNSET, favFood = "berry", reaction = "Как ласково! 🌸"),
        PetBlueprint("Летучая Мышка", PetRarity.RARE, PetSilhouette.GHOST_FLOAT, HeadShape.ROUND, EarType.BAT_EARS, HairStyle.NONE, BackFeature.BAT_WINGS, FaceStyle.FANG, MarkingType.HEART_CHEST, EyeStyle.ANIME, PetPalette.LAVENDER, favFood = "berry", reaction = "Фыр-фыр, ушки чешутся! ♥"),
        PetBlueprint("Хомячок", PetRarity.COMMON, PetSilhouette.CAPYBARA_ROUND, HeadShape.PEAR, EarType.BEAR, HairStyle.FLUFF_TUFT, BackFeature.NONE, FaceStyle.CAT_W, MarkingType.CHEEK_MARKS, EyeStyle.BUTTON, PetPalette.SUNSET, favFood = "donut", reaction = "Щёчки аж покраснели!"),
        PetBlueprint("Ёжик", PetRarity.COMMON, PetSilhouette.CAPYBARA_ROUND, HeadShape.SUN_SPIKY, EarType.BEAR, HairStyle.NONE, BackFeature.DINO_SPIKES, FaceStyle.SNOUT, MarkingType.BELLY, EyeStyle.BUTTON, PetPalette.CHOCO, favFood = "berry", reaction = "Сворачиваю колючки ради тебя! ♥"),
        PetBlueprint("Коала", PetRarity.COMMON, PetSilhouette.CLASSIC_CHIBI, HeadShape.WIDE, EarType.MONKEY_EARS, HairStyle.FLUFF_TUFT, BackFeature.NONE, FaceStyle.SNOUT, MarkingType.BELLY, EyeStyle.SLEEPY, PetPalette.SNOW, favFood = "onigiri", reaction = "Мягко и сонно... ♥"),
        PetBlueprint("Альпака", PetRarity.RARE, PetSilhouette.BUNNY_PLUSH, HeadShape.CLOUD, EarType.BUNNY, HairStyle.FLUFF_TUFT, BackFeature.FLUFFY_TAIL, FaceStyle.CAT_W, MarkingType.BELLY, EyeStyle.SPARKLE_GEM, PetPalette.SNOW, favFood = "icecream", reaction = "Пушистое счастье! ☁️"),
        PetBlueprint("Тигрёнок", PetRarity.RARE, PetSilhouette.CLASSIC_CHIBI, HeadShape.ROUND, EarType.CAT, HairStyle.LION_MANE, BackFeature.CAT_TAIL, FaceStyle.FANG, MarkingType.STRIPES, EyeStyle.ANIME, PetPalette.SUNSET, favFood = "burger", reaction = "Ррр-мурр! ♥"),
        PetBlueprint("Львёнок", PetRarity.RARE, PetSilhouette.CLASSIC_CHIBI, HeadShape.SUN_SPIKY, EarType.BEAR, HairStyle.LION_MANE, BackFeature.CAT_TAIL, FaceStyle.SNOUT, MarkingType.BELLY, EyeStyle.ANIME, PetPalette.GOLD, favFood = "burger", reaction = "Грива довольна! 👑"),
        PetBlueprint("Сиба-Ину", PetRarity.COMMON, PetSilhouette.FOX_SLENDER, HeadShape.ROUND, EarType.FOX, HairStyle.NONE, BackFeature.FLUFFY_TAIL, FaceStyle.SNOUT, MarkingType.TUXEDO, EyeStyle.ANIME, PetPalette.SUNSET, favFood = "ramen", reaction = "Довольная улыбочка! ♥"),
        PetBlueprint("Утёнок", PetRarity.COMMON, PetSilhouette.BIRD_OWL, HeadShape.ROUND, EarType.NONE, HairStyle.FLUFF_TUFT, BackFeature.ANGEL_WINGS, FaceStyle.BEAK, MarkingType.BELLY, EyeStyle.BUTTON, PetPalette.GOLD, favFood = "berry", reaction = "Кря-кря, как приятно!"),
        PetBlueprint("Фламинго", PetRarity.RARE, PetSilhouette.BIRD_OWL, HeadShape.OVAL, EarType.NONE, HairStyle.CREST_FIN, BackFeature.ANGEL_WINGS, FaceStyle.BEAK, MarkingType.BELLY, EyeStyle.SPARKLE_GEM, PetPalette.SAKURA, favFood = "sushi", reaction = "Розовый восторг! 🌸"),
        PetBlueprint("Попугай", PetRarity.COMMON, PetSilhouette.BIRD_OWL, HeadShape.ROUND, EarType.NONE, HairStyle.CREST_FIN, BackFeature.FAIRY_WINGS, FaceStyle.BEAK, MarkingType.CHEEK_MARKS, EyeStyle.ANIME, PetPalette.MINT, favFood = "berry", reaction = "Чирик! Люблю тебя!"),
        PetBlueprint("Черепашка", PetRarity.COMMON, PetSilhouette.TURTLE_SHELL, HeadShape.ROUND, EarType.NONE, HairStyle.NONE, BackFeature.TURTLE_CARAPACE, FaceStyle.KAWAII, MarkingType.SPOTS, EyeStyle.BUTTON, PetPalette.MINT, favFood = "berry", reaction = "Выглядываю из панциря! ♥"),
        PetBlueprint("Осьминожка", PetRarity.RARE, PetSilhouette.OCTO_TENTACLES, HeadShape.PEAR, EarType.FIN_EARS, HairStyle.NONE, BackFeature.NONE, FaceStyle.CAT_W, MarkingType.SPOTS, EyeStyle.ANIME, PetPalette.BERRY, favFood = "sushi", reaction = "Обнимаю восемью лапками!"),
        PetBlueprint("Медуза", PetRarity.RARE, PetSilhouette.OCTO_TENTACLES, HeadShape.CLOUD, EarType.NONE, HairStyle.WATER_SPOUT, BackFeature.AURA_RING, FaceStyle.STAR_BLUSH, MarkingType.SPOTS, EyeStyle.STARRY, PetPalette.SKY, favFood = "icecream", reaction = "Мягко свечусь от радости! ✨"),
        PetBlueprint("Китёнок", PetRarity.RARE, PetSilhouette.WHALE_FIN, HeadShape.WIDE, EarType.FIN_EARS, HairStyle.WATER_SPOUT, BackFeature.NONE, FaceStyle.KAWAII, MarkingType.BELLY, EyeStyle.BUTTON, PetPalette.SKY, favFood = "sushi", reaction = "Фонтанчик счастья! 🐳"),
        PetBlueprint("Нарвал", PetRarity.EPIC, PetSilhouette.WHALE_FIN, HeadShape.WIDE, EarType.UNICORN_HORN, HairStyle.WATER_SPOUT, BackFeature.AURA_RING, FaceStyle.STAR_BLUSH, MarkingType.STAR_FOREHEAD, EyeStyle.STARRY, PetPalette.LAVENDER, favFood = "icecream", reaction = "Рожок блестит! ✨"),
        PetBlueprint("Дельфин", PetRarity.COMMON, PetSilhouette.WHALE_FIN, HeadShape.OVAL, EarType.FIN_EARS, HairStyle.CREST_FIN, BackFeature.NONE, FaceStyle.KAWAII, MarkingType.BELLY, EyeStyle.ANIME, PetPalette.SKY, favFood = "sushi", reaction = "Прыг от радости!"),
        PetBlueprint("Пчёлка", PetRarity.COMMON, PetSilhouette.BEE_BUG, HeadShape.ROUND, EarType.BUG_FEELERS, HairStyle.NONE, BackFeature.FAIRY_WINGS, FaceStyle.KAWAII, MarkingType.STRIPES, EyeStyle.ANIME, PetPalette.GOLD, favFood = "cake", reaction = "Жжж-как сладко! 🍯"),
        PetBlueprint("Бабочка", PetRarity.RARE, PetSilhouette.BEE_BUG, HeadShape.OVAL, EarType.BUG_FEELERS, HairStyle.FLOWER_CROWN, BackFeature.FAIRY_WINGS, FaceStyle.STAR_BLUSH, MarkingType.SPOTS, EyeStyle.SPARKLE_GEM, PetPalette.SAKURA, favFood = "berry", reaction = "Порхаю вокруг тебя! 🌸"),
        PetBlueprint("Змейка", PetRarity.RARE, PetSilhouette.SNAKE_COIL, HeadShape.DIAMOND, EarType.LEAF_SPROUT, HairStyle.NONE, BackFeature.NONE, FaceStyle.FANG, MarkingType.STRIPES, EyeStyle.ANIME, PetPalette.MINT, favFood = "onigiri", reaction = "Шшш-как хорошо! ♥"),
        PetBlueprint("Трицератопс", PetRarity.RARE, PetSilhouette.TURTLE_SHELL, HeadShape.WIDE, EarType.DRAGON_HORNS, HairStyle.CREST_FIN, BackFeature.DINO_SPIKES, FaceStyle.SNOUT, MarkingType.SPOTS, EyeStyle.BUTTON, PetPalette.SUNSET, favFood = "berry", reaction = "Довольно топаю лапками!"),
        PetBlueprint("Кицунэ", PetRarity.LEGENDARY, PetSilhouette.FOX_SLENDER, HeadShape.DIAMOND, EarType.FOX, HairStyle.FLOWER_CROWN, BackFeature.FLUFFY_TAIL, FaceStyle.STAR_BLUSH, MarkingType.CHEEK_MARKS, EyeStyle.STARRY, PetPalette.FLAME, favFood = "ramen", reaction = "Девять хвостов пушатся! ✨"),
        PetBlueprint("Феникс", PetRarity.LEGENDARY, PetSilhouette.BIRD_OWL, HeadShape.SUN_SPIKY, EarType.DRAGON_HORNS, HairStyle.CREST_FIN, BackFeature.ANGEL_WINGS, FaceStyle.BEAK, MarkingType.STAR_FOREHEAD, EyeStyle.STARRY, PetPalette.FLAME, favFood = "cake", reaction = "Тёплое сияние! 🔥"),
        PetBlueprint("Космо-Дракон", PetRarity.LEGENDARY, PetSilhouette.DRAGON_WINGED, HeadShape.DIAMOND, EarType.DRAGON_HORNS, HairStyle.CREST_FIN, BackFeature.BAT_WINGS, FaceStyle.FANG, MarkingType.STAR_FOREHEAD, EyeStyle.STARRY, PetPalette.GALAXY, favFood = "icecream", reaction = "Звёздное мурчание! ✨"),
        PetBlueprint("Меха-Дракон", PetRarity.EPIC, PetSilhouette.MECHA_BOT, HeadShape.SQUARE_TV, EarType.DRAGON_HORNS, HairStyle.NONE, BackFeature.JETPACK, FaceStyle.VISOR, MarkingType.STRIPES, EyeStyle.CYBER, PetPalette.CYBER, favFood = "burger", reaction = "Броня тает от ласки! ⚡"),
        PetBlueprint("Фея Леса", PetRarity.EPIC, PetSilhouette.HERO_CHIBI, HeadShape.CHIBI_HUMAN, EarType.LEAF_SPROUT, HairStyle.FLOWER_CROWN, BackFeature.FAIRY_WINGS, FaceStyle.STAR_BLUSH, MarkingType.HEART_CHEST, EyeStyle.SPARKLE_GEM, PetPalette.MINT, favFood = "berry", reaction = "Цветочки расцветают! 🌸"),
        PetBlueprint("Цукумо-Облачко", PetRarity.EPIC, PetSilhouette.GHOST_FLOAT, HeadShape.CLOUD, EarType.HEART_ANTENNA, HairStyle.NONE, BackFeature.AURA_RING, FaceStyle.STAR_BLUSH, MarkingType.CHEEK_MARKS, EyeStyle.SLEEPY, PetPalette.SNOW, favFood = "icecream", reaction = "Воздушный восторг! ☁️")
    )

    private val extraSpeciesNames = listOf(
        "Волчонок", "Тюлень", "Выдра", "Мейн-Кун", "Фенек",
        "Сурикат", "Ленивец", "Белка", "Бурундук", "Шиншилла",
        "Хорёк", "Рысь", "Пантера", "Гепард", "Снежный Барс",
        "Полярный Мишка", "Цыплёнок", "Тукан", "Ворон", "Павлин",
        "Лебедь", "Колибри", "Акулёнок", "Краб", "Морской Конёк",
        "Морж", "Светлячок", "Божья Коровка", "Стрекоза", "Хамелеон",
        "Геккон", "Саламандра", "Тритон", "Игуана", "Стегуша",
        "Птера", "Мамонт", "Саблезуб", "Бобёр", "Лама",
        "Овечка", "Кролик-Рекс", "Грифон", "Пегас", "Тануки",
        "Кибер-Кот", "Нано-Пёс", "Пиксель-Бот", "Дрончик", "Демонёнок",
        "Эльфик", "Голем", "Снеговик", "Звёздочка", "Лунатик",
        "Пришелец", "Кристаллик", "Маг-Сова", "Рыцарь-Мишка", "Пират-Попугай",
        "Сфинкс", "Цербер", "Кракен", "Йети", "Домовой",
        "Леший", "Русалка", "Валькирия", "Анубис", "Манул",
        "Утконос", "Броненосец", "Кенгуру", "Тушканчик", "Лори",
        "Сахарный Поссум", "Мандаринка", "Косатка", "Скат", "Астронавт"
    )

    val allPets: List<PetDefinition> = buildList {
        // 0..14: 15 СТАРТОВЫХ С ПЛАВНЫМИ РАЗНЫМИ ФОРМАМИ
        add(PetDefinition(0, "Котик", PetRarity.COMMON, true, "Ласковый", PetPalette.SAKURA, PetSilhouette.CLASSIC_CHIBI, HeadShape.ROUND, EarType.CAT, HairStyle.NONE, BackFeature.CAT_TAIL, FaceStyle.CAT_W, MarkingType.BELLY, EyeStyle.ANIME, favoriteFoodId = "sushi", pettingReaction = "Мур-мур~ Обожаю! ♥"))
        add(PetDefinition(1, "Зайка", PetRarity.COMMON, true, "Игривый", PetPalette.SNOW, PetSilhouette.BUNNY_PLUSH, HeadShape.OVAL, EarType.BUNNY, HairStyle.NONE, BackFeature.FLUFFY_TAIL, FaceStyle.CAT_W, MarkingType.HEART_CHEST, EyeStyle.SPARKLE_GEM, favoriteFoodId = "berry", pettingReaction = "Ушки шевелятся от радости! ♥"))
        add(PetDefinition(2, "Дракоша", PetRarity.RARE, true, "Храбрый", PetPalette.MINT, PetSilhouette.DRAGON_WINGED, HeadShape.DIAMOND, EarType.DRAGON_HORNS, HairStyle.CREST_FIN, BackFeature.BAT_WINGS, FaceStyle.FANG, MarkingType.STRIPES, EyeStyle.ANIME, favoriteFoodId = "burger", pettingReaction = "Ррр-мурр! Так приятно!"))
        add(PetDefinition(3, "Слайм", PetRarity.COMMON, true, "Сладкоежка", PetPalette.SKY, PetSilhouette.SLIME_JELLY, HeadShape.PEAR, EarType.LEAF_SPROUT, HairStyle.WATER_SPOUT, BackFeature.NONE, FaceStyle.KAWAII, MarkingType.CHEEK_MARKS, EyeStyle.BUTTON, favoriteFoodId = "donut", pettingReaction = "Буль-буль~ Мягко! ♥"))
        add(PetDefinition(4, "Мишка", PetRarity.COMMON, true, "Добрый", PetPalette.CHOCO, PetSilhouette.CAPYBARA_ROUND, HeadShape.ROUND, EarType.BEAR, HairStyle.NONE, BackFeature.NONE, FaceStyle.SNOUT, MarkingType.BELLY, EyeStyle.BUTTON, favoriteFoodId = "cake", pettingReaction = "Обнимашки самые тёплые! ♥"))
        add(PetDefinition(5, "Лисичка", PetRarity.COMMON, true, "Озорной", PetPalette.SUNSET, PetSilhouette.FOX_SLENDER, HeadShape.DIAMOND, EarType.FOX, HairStyle.FLUFF_TUFT, BackFeature.FLUFFY_TAIL, FaceStyle.WHISKERS, MarkingType.CHEEK_MARKS, EyeStyle.ANIME, favoriteFoodId = "onigiri", pettingReaction = "Хи-хи! Погладь за ушком!"))
        add(PetDefinition(6, "Аксолотль", PetRarity.RARE, true, "Любопытный", PetPalette.SAKURA, PetSilhouette.AXOLOTL_AQUA, HeadShape.WIDE, EarType.NONE, HairStyle.NONE, BackFeature.AXO_GILLS, FaceStyle.KAWAII, MarkingType.SPOTS, EyeStyle.BUTTON, favoriteFoodId = "sushi", pettingReaction = "Буль! Улыбаюсь до ушей! ♥"))
        add(PetDefinition(7, "Пингвин", PetRarity.COMMON, true, "Сонный", PetPalette.SKY, PetSilhouette.BIRD_OWL, HeadShape.OVAL, EarType.NONE, HairStyle.FLUFF_TUFT, BackFeature.NONE, FaceStyle.BEAK, MarkingType.TUXEDO, EyeStyle.ANIME, favoriteFoodId = "icecream", pettingReaction = "Плавнички хлопают от счастья!"))
        add(PetDefinition(8, "Дино", PetRarity.COMMON, true, "Храбрый", PetPalette.MINT, PetSilhouette.DRAGON_WINGED, HeadShape.WIDE, EarType.DRAGON_HORNS, HairStyle.NONE, BackFeature.DINO_SPIKES, FaceStyle.FANG, MarkingType.SPOTS, EyeStyle.ANIME, favoriteFoodId = "burger", pettingReaction = "Аррр~ Это так мило!"))
        add(PetDefinition(9, "Призрак", PetRarity.RARE, true, "Загадочный", PetPalette.LAVENDER, PetSilhouette.GHOST_FLOAT, HeadShape.CLOUD, EarType.BAT_EARS, HairStyle.NONE, BackFeature.AURA_RING, FaceStyle.STAR_BLUSH, MarkingType.STAR_FOREHEAD, EyeStyle.SLEEPY, favoriteFoodId = "cake", pettingReaction = "Буу~ Как тепло на душе! ♥"))
        add(PetDefinition(10, "Робот", PetRarity.RARE, true, "Умник", PetPalette.CYBER, PetSilhouette.MECHA_BOT, HeadShape.SQUARE_TV, EarType.ANTENNA, HairStyle.TV_SCREEN, BackFeature.JETPACK, FaceStyle.VISOR, MarkingType.HEART_CHEST, EyeStyle.CYBER, HeroSpecial.RETRO_BOT, "donut", "Бип-буп! Уровень любви +100% ♥"))
        add(PetDefinition(11, "Единорог", PetRarity.RARE, true, "Мечтатель", PetPalette.LAVENDER, PetSilhouette.CLASSIC_CHIBI, HeadShape.CLOUD, EarType.UNICORN_HORN, HairStyle.LION_MANE, BackFeature.FLUFFY_TAIL, FaceStyle.STAR_BLUSH, MarkingType.STAR_FOREHEAD, EyeStyle.STARRY, favoriteFoodId = "icecream", pettingReaction = "Радужные искры от твоих рук! ✨"))
        add(PetDefinition(12, "Пёсик", PetRarity.COMMON, true, "Добрый", PetPalette.SUNSET, PetSilhouette.CLASSIC_CHIBI, HeadShape.ROUND, EarType.PUPPY, HairStyle.NONE, BackFeature.CAT_TAIL, FaceStyle.SNOUT, MarkingType.SPOTS, EyeStyle.ANIME, favoriteFoodId = "burger", pettingReaction = "Гав-гав! Ещё погладь! ♥"))
        add(PetDefinition(13, "Лягушка", PetRarity.COMMON, true, "Игривый", PetPalette.MINT, PetSilhouette.SLIME_JELLY, HeadShape.WIDE, EarType.FROG_EYES, HairStyle.NONE, BackFeature.NONE, FaceStyle.KAWAII, MarkingType.BELLY, EyeStyle.BUTTON, favoriteFoodId = "berry", pettingReaction = "Ква-ква! Так хорошо! ♥"))
        add(PetDefinition(14, "Ангелок", PetRarity.RARE, true, "Звёздный", PetPalette.GOLD, PetSilhouette.HERO_CHIBI, HeadShape.CHIBI_HUMAN, EarType.NONE, HairStyle.FLOWER_CROWN, BackFeature.ANGEL_WINGS, FaceStyle.STAR_BLUSH, MarkingType.HEART_CHEST, EyeStyle.STARRY, favoriteFoodId = "cake", pettingReaction = "Несу свет и обнимашки! ✨"))

        // Курированные уникальные герои и существа
        specialUnlockables.forEachIndexed { i, bp ->
            val id = 15 + i
            add(
                PetDefinition(
                    id = id,
                    name = bp.name,
                    rarity = bp.rarity,
                    isStarter = false,
                    personality = personalities[(id * 5) % personalities.size],
                    defaultPalette = bp.palette,
                    silhouette = bp.silhouette,
                    head = bp.head,
                    ears = bp.ears,
                    hair = bp.hair,
                    back = bp.back,
                    face = bp.face,
                    marking = bp.marking,
                    eyes = bp.eyes,
                    heroSpecial = bp.hero,
                    favoriteFoodId = bp.favFood,
                    pettingReaction = bp.reaction
                )
            )
        }

        // Остальные виды с разнообразными формами головы (включая STAR_FIVE, SUN_SPIKY, CLOUD, PEAR, CHIBI_HUMAN)
        val heads = HeadShape.entries.filter { it != HeadShape.SQUARE_TV }
        val hairs = HairStyle.entries.filter { it != HairStyle.TV_SCREEN }
        val markings = MarkingType.entries
        val eyes = EyeStyle.entries
        val palettes = PetPalette.entries
        val foods = FoodCatalog.items
        val baseId = 15 + specialUnlockables.size

        extraSpeciesNames.forEachIndexed { idx, name ->
            val id = baseId + idx
            val rarity = when {
                id >= 115 -> PetRarity.LEGENDARY
                id >= 92 -> PetRarity.EPIC
                id >= 65 -> PetRarity.RARE
                else -> PetRarity.COMMON
            }
            val sil = when {
                name.contains("Кот") || name.contains("Кун") || name.contains("Манул") || name.contains("Рысь") || name.contains("Пантера") || name.contains("Гепард") || name.contains("Барс") -> PetSilhouette.CLASSIC_CHIBI
                name.contains("Волч") || name.contains("Фенек") || name.contains("Белк") || name.contains("Хорёк") || name.contains("Тануки") || name.contains("Поссум") -> PetSilhouette.FOX_SLENDER
                name.contains("Крол") || name.contains("Кенгур") -> PetSilhouette.BUNNY_PLUSH
                name.contains("Миш") || name.contains("Боб") || name.contains("Мамонт") || name.contains("Йети") || name.contains("Ленивец") || name.contains("Лама") || name.contains("Овеч") -> PetSilhouette.CAPYBARA_ROUND
                name.contains("Цып") || name.contains("Тукан") || name.contains("Ворон") || name.contains("Павл") || name.contains("Лебед") || name.contains("Колиб") || name.contains("Сова") || name.contains("Попуг") || name.contains("Мандарин") -> PetSilhouette.BIRD_OWL
                name.contains("Акул") || name.contains("Тюлен") || name.contains("Выдр") || name.contains("Морж") || name.contains("Косат") || name.contains("Скат") -> PetSilhouette.WHALE_FIN
                name.contains("Светляч") || name.contains("Коров") || name.contains("Стрекоз") -> PetSilhouette.BEE_BUG
                name.contains("Краб") || name.contains("Кракен") -> PetSilhouette.OCTO_TENTACLES
                name.contains("Броненосец") || name.contains("Голем") -> PetSilhouette.TURTLE_SHELL
                name.contains("Стег") || name.contains("Птера") || name.contains("Геккон") || name.contains("Саламандр") || name.contains("Тритон") || name.contains("Игуан") || name.contains("Хамел") -> PetSilhouette.DRAGON_WINGED
                name.contains("Бот") || name.contains("Дрон") || name.contains("Кибер") || name.contains("Нано") || name.contains("Астрон") -> PetSilhouette.MECHA_BOT
                name.contains("Звёзд") || name.contains("Луна") || name.contains("Пришел") || name.contains("Эльф") || name.contains("Русал") || name.contains("Валькир") || name.contains("Домовой") || name.contains("Леший") -> PetSilhouette.HERO_CHIBI
                else -> PetSilhouette.entries[(id * 3 + 1) % PetSilhouette.entries.size]
            }
            val head = when {
                name.contains("Звёзд") || name.contains("Луна") || name.contains("Кристал") -> HeadShape.STAR_FIVE
                name.contains("Светляч") || name.contains("Цып") -> HeadShape.SUN_SPIKY
                name.contains("Овеч") || name.contains("Йети") || name.contains("Снеговик") -> HeadShape.CLOUD
                sil == PetSilhouette.HERO_CHIBI -> HeadShape.CHIBI_HUMAN
                sil == PetSilhouette.MECHA_BOT -> HeadShape.SQUARE_TV
                else -> heads[(id * 5 + 2) % heads.size]
            }
            val ear = when {
                head == HeadShape.STAR_FIVE -> EarType.HEART_ANTENNA
                sil == PetSilhouette.BIRD_OWL && name.contains("Сов") -> EarType.OWL_TUFTS
                sil == PetSilhouette.BIRD_OWL -> EarType.NONE
                sil == PetSilhouette.WHALE_FIN -> EarType.FIN_EARS
                sil == PetSilhouette.BEE_BUG -> EarType.BUG_FEELERS
                sil == PetSilhouette.MECHA_BOT -> EarType.ANTENNA
                sil == PetSilhouette.BUNNY_PLUSH -> EarType.BUNNY
                sil == PetSilhouette.FOX_SLENDER -> EarType.FOX
                name.contains("Сфинкс") || name.contains("Манул") || name.contains("Кот") || name.contains("Кун") || name.contains("Рысь") || name.contains("Пантер") || name.contains("Гепард") || name.contains("Барс") -> EarType.CAT
                name.contains("Дем") || name.contains("Дракон") -> EarType.DRAGON_HORNS
                name.contains("Пегас") -> EarType.UNICORN_HORN
                name.contains("Пёс") || name.contains("Анубис") || name.contains("Цербер") -> EarType.PUPPY
                else -> EarType.entries[(id * 5 + 2) % EarType.entries.size]
            }
            val back = when {
                name.contains("Птера") || name.contains("Дем") || name.contains("Грифон") -> BackFeature.BAT_WINGS
                name.contains("Пегас") || name.contains("Валькир") || sil == PetSilhouette.BIRD_OWL -> BackFeature.ANGEL_WINGS
                sil == PetSilhouette.BEE_BUG -> BackFeature.FAIRY_WINGS
                name.contains("Стег") || name.contains("Игуан") || name.contains("Саблез") -> BackFeature.DINO_SPIKES
                sil == PetSilhouette.MECHA_BOT -> BackFeature.JETPACK
                rarity == PetRarity.LEGENDARY -> BackFeature.AURA_RING
                sil == PetSilhouette.FOX_SLENDER -> BackFeature.FLUFFY_TAIL
                else -> BackFeature.entries[(id * 3) % BackFeature.entries.size]
            }
            val face = when {
                sil == PetSilhouette.BIRD_OWL -> FaceStyle.BEAK
                sil == PetSilhouette.MECHA_BOT -> FaceStyle.VISOR
                name.contains("Саблез") || name.contains("Дем") || name.contains("Цербер") -> FaceStyle.FANG
                name.contains("Кот") || name.contains("Манул") || name.contains("Рысь") -> FaceStyle.CAT_W
                else -> FaceStyle.entries[(id * 4 + 1) % FaceStyle.entries.size]
            }
            add(
                PetDefinition(
                    id = id,
                    name = name,
                    rarity = rarity,
                    isStarter = false,
                    personality = personalities[(id * 7) % personalities.size],
                    defaultPalette = palettes[(id * 3 + 2) % palettes.size],
                    silhouette = sil,
                    head = head,
                    ears = ear,
                    hair = if (sil == PetSilhouette.HERO_CHIBI) HairStyle.SWOOP_HAIR else if (id % 4 == 0) hairs[(id * 2) % hairs.size] else HairStyle.NONE,
                    back = back,
                    face = face,
                    marking = markings[(id * 5) % markings.size],
                    eyes = if (face == FaceStyle.VISOR) EyeStyle.CYBER else if (rarity == PetRarity.LEGENDARY) EyeStyle.STARRY else eyes[(id * 2) % eyes.size],
                    favoriteFoodId = foods[id % foods.size].id,
                    pettingReaction = affectionPhrases[id % affectionPhrases.size]
                )
            )
        }
    }

    val starters: List<PetDefinition> = allPets.filter { it.isStarter }

    fun getById(id: Int): PetDefinition =
        allPets.getOrElse(id.coerceIn(0, allPets.lastIndex)) { allPets.first() }
}

// --- АВТОМАТ КАПСУЛ ---
data class GachaCapsule(
    val id: String,
    val name: String,
    val emoji: String,
    val price: Int,
    val oddsText: String,
    val colorHex: Long,
    val wCommon: Int,
    val wRare: Int,
    val wEpic: Int,
    val wLegend: Int
)

object GachaCatalog {
    val capsules = listOf(
        GachaCapsule("cap_basic", "Обычная капсула", "🥚", 60, "Обычн 55% • Редк 32% • Эпик 10% • Лег 3%", 0xFF56CCF2L, 55, 32, 10, 3),
        GachaCapsule("cap_rare", "Редкая капсула", "💎", 140, "Редк 52% • Эпик 36% • Лег 12%", 0xFFC77DFFL, 0, 52, 36, 12),
        GachaCapsule("cap_legend", "Легендарная капсула", "👑", 280, "Гарантия Эпик 60% • Легенда 40%!", 0xFFFFD166L, 0, 0, 60, 40)
    )

    fun roll(capsule: GachaCapsule): Pair<PetDefinition, PetPalette> {
        val total = capsule.wCommon + capsule.wRare + capsule.wEpic + capsule.wLegend
        val r = Random.nextInt(total)
        val rarity = when {
            r < capsule.wCommon -> PetRarity.COMMON
            r < capsule.wCommon + capsule.wRare -> PetRarity.RARE
            r < capsule.wCommon + capsule.wRare + capsule.wEpic -> PetRarity.EPIC
            else -> PetRarity.LEGENDARY
        }
        val pool = PetCatalog.allPets.filter { !it.isStarter && it.rarity == rarity }
            .ifEmpty { PetCatalog.allPets.filter { it.rarity == rarity } }
        val pet = pool[Random.nextInt(pool.size)]
        val randomColor = PetPalette.random()
        return pet to randomColor
    }
}

data class FoodItem(
    val id: String,
    val name: String,
    val emoji: String,
    val isSnack: Boolean,
    val hunger: Int,
    val mood: Int,
    val weight: Int,
    val price: Int
)

object FoodCatalog {
    val items = listOf(
        FoodItem("onigiri", "Онигири", "🍙", false, 24, 6, 1, 0),
        FoodItem("ramen", "Рамен", "🍜", false, 36, 12, 2, 10),
        FoodItem("sushi", "Суши-сет", "🍣", false, 45, 16, 2, 20),
        FoodItem("burger", "Пиксель-бургер", "🍔", false, 52, 14, 4, 15),
        FoodItem("berry", "Клубничка", "🍓", true, 8, 22, 1, 0),
        FoodItem("donut", "Пончик", "🍩", true, 12, 30, 3, 10),
        FoodItem("icecream", "Мороженое", "🍦", true, 10, 34, 2, 12),
        FoodItem("cake", "Тортик", "🍰", true, 18, 45, 4, 25)
    )
}

// =====================================================================
// ЭТАП 2: ГОРОД, ЛОКАЦИИ, НАВЫКИ, ШТУЧКИ-ТРОФЕИ И КРАФТ
// =====================================================================

enum class SkillType(val title: String, val emoji: String, val colorHex: Long) {
    INTELLECT("Интеллект", "🧠", 0xFF56CCF2L),
    AGILITY("Ловкость", "⚡", 0xFF7BE495L),
    CHARISMA("Харизма", "🌟", 0xFFFF6B9DL),
    CREATIVITY("Творчество", "🎨", 0xFFFFD166L)
}

data class CollectibleItem(
    val id: String,
    val name: String,
    val emoji: String,
    val source: String,
    val rarity: PetRarity,
    val moodBoost: Int,
    val affectionBoost: Int
)

object CollectibleCatalog {
    val items = listOf(
        CollectibleItem("clover", "Клевер удачи", "🍀", "Парк", PetRarity.COMMON, 18, 6),
        CollectibleItem("bookmark", "Закладка-котик", "🔖", "Школа", PetRarity.COMMON, 15, 5),
        CollectibleItem("latte", "Латте с пенкой", "☕", "Кафе-работа", PetRarity.COMMON, 20, 7),
        CollectibleItem("popcorn", "Сладкий попкорн", "🍿", "Кинотеатр", PetRarity.COMMON, 22, 6),
        CollectibleItem("compass", "Звёздный циркуль", "🧭", "Школа", PetRarity.RARE, 25, 10),
        CollectibleItem("cartridge", "Ретро-картридж", "🕹️", "Геймдев", PetRarity.RARE, 28, 12),
        CollectibleItem("glasses3d", "Билет VIP-кино", "🎟️", "Кинотеатр", PetRarity.RARE, 24, 9),
        CollectibleItem("sakura_branch", "Веточка сакуры", "🌸", "Япония", PetRarity.RARE, 30, 14),
        CollectibleItem("shell", "Жемчужная ракушка", "🐚", "Тропики", PetRarity.RARE, 28, 12),
        CollectibleItem("neon_chip", "Кибер-чип", "💾", "Кибер-Сити", PetRarity.EPIC, 35, 16),
        CollectibleItem("gold_ticket", "Золотой билет", "🎫", "Сцена айдола", PetRarity.EPIC, 38, 18),
        CollectibleItem("moon_crystal", "Лунный кристалл", "🔮", "Космос", PetRarity.LEGENDARY, 50, 25)
    )

    fun getById(id: String): CollectibleItem? = items.find { it.id == id }
}

data class CraftRecipe(
    val id: String,
    val rewardWardrobeId: String,
    val title: String,
    val emoji: String,
    val ingredients: Map<String, Int>
)

object CraftCatalog {
    val recipes = listOf(
        CraftRecipe("cr_beret", "w_beret_1", "Берет художника", "🎨", mapOf("clover" to 1, "bookmark" to 1)),
        CraftRecipe("cr_boba", "w_boba_tea_1", "Сладкий Бабл-Ти", "🧋", mapOf("latte" to 1, "popcorn" to 1)),
        CraftRecipe("cr_visor", "w_cyber_visor_2", "Кибер-Визор", "🤖", mapOf("cartridge" to 1, "neon_chip" to 1)),
        CraftRecipe("cr_sakura", "w_sakura_petals_1", "Вихрь Сакуры", "🌸", mapOf("sakura_branch" to 1, "clover" to 1)),
        CraftRecipe("cr_crown", "w_crown_3", "Золотая Корона", "👑", mapOf("gold_ticket" to 1, "moon_crystal" to 1)),
        CraftRecipe("cr_wand", "w_magic_wand_3", "Звёздный Жезл", "🪄", mapOf("compass" to 1, "moon_crystal" to 1))
    )
}

data class SchoolLesson(
    val id: String,
    val title: String,
    val emoji: String,
    val skill: SkillType,
    val skillGain: Int,
    val iqGain: Int,
    val energyCost: Int,
    val dropPool: List<String>
)

data class CityJob(
    val id: String,
    val title: String,
    val emoji: String,
    val reqIntellect: Int,
    val reqAgility: Int,
    val reqCharisma: Int,
    val payCoins: Int,
    val energyCost: Int,
    val dropId: String
)

data class TravelTrip(
    val id: String,
    val title: String,
    val emoji: String,
    val price: Int,
    val moodGain: Int,
    val creativityGain: Int,
    val dropId: String,
    val postcardText: String
)

object CityCatalog {
    val lessons = listOf(
        SchoolLesson("math", "Логика и Код", "💻", SkillType.INTELLECT, 4, 5, 10, listOf("bookmark", "compass")),
        SchoolLesson("sport", "Прыжки и Спринт", "🏃", SkillType.AGILITY, 4, 2, 12, listOf("clover", "compass")),
        SchoolLesson("stage", "Вокал и Сцена", "🎤", SkillType.CHARISMA, 4, 3, 10, listOf("bookmark", "gold_ticket")),
        SchoolLesson("art", "Пиксель-Арт", "🎨", SkillType.CREATIVITY, 4, 4, 10, listOf("bookmark", "sakura_branch"))
    )

    val jobs = listOf(
        CityJob("barista", "Бариста в Кото-кафе", "☕", 0, 0, 0, 40, 12, "latte"),
        CityJob("gamedev", "Разработчик игр", "🕹️", 12, 0, 0, 75, 15, "cartridge"),
        CityJob("idol", "Суперзвезда сцены", "🌟", 0, 8, 18, 110, 16, "gold_ticket"),
        CityJob("astronaut", "Космонавт", "🚀", 24, 18, 10, 165, 18, "moon_crystal")
    )

    val trips = listOf(
        TravelTrip("japan", "Киото (Сакура)", "⛩️", 55, 25, 4, "sakura_branch", "Прогулка под лепестками сакуры в Киото!"),
        TravelTrip("tropics", "Тропический остров", "🏝️", 65, 28, 3, "shell", "Солнечный пляж и шум океана!"),
        TravelTrip("cyber", "Неон-Токио", "🌃", 85, 30, 5, "neon_chip", "Огни ночного Кибер-Сити!"),
        TravelTrip("moon", "Лунная база", "🌕", 120, 40, 8, "moon_crystal", "Невесомость среди ярких звёзд!")
    )
}

data class RoomTheme(
    val id: String,
    val name: String,
    val emoji: String,
    val price: Int,
    val wallMain: Long,
    val wallStripe: Long,
    val wainscot: Long,
    val floorA: Long,
    val floorB: Long,
    val rugOuter: Long,
    val rugInner: Long,
    val curtain: Long,
    val bedBlanket: Long
)

object RoomThemeCatalog {
    val themes = listOf(
        RoomTheme("sakura", "Уютная Сакура", "🌸", 0, 0xFFFFF0E6L, 0xFFFFD9E8L, 0xFFE3B798L, 0xFFE8B478L, 0xFFD9946CL, 0xFFFF7EB3L, 0xFFFFD4E6L, 0xFFFF78B4L, 0xFF9B7BFFL),
        RoomTheme("cyber", "Киберпанк Неон", "🌃", 90, 0xFF1A1538L, 0xFF231C4AL, 0xFF332669L, 0xFF16132BL, 0xFF1E1A3BL, 0xFF00E5FFL, 0xFF1E325CL, 0xFFFF2E93L, 0xFF00D2D3L),
        RoomTheme("space", "Космо-станция", "🚀", 120, 0xFF1B2440L, 0xFF243054L, 0xFF3B4B75L, 0xFF2E3856L, 0xFF394569L, 0xFF7B61FFL, 0xFFC7B8FFL, 0xFF56CCF2L, 0xFFFF6B9DL),
        RoomTheme("forest", "Лесной домик", "🌲", 80, 0xFFEAF7E4L, 0xFFD9F0D0L, 0xFFA67C52L, 0xFFA87143L, 0xFF946137L, 0xFF52B788L, 0xFFB7E4C7L, 0xFF40916CL, 0xFFFFB703L),
        RoomTheme("beach", "Тропики", "🏖️", 100, 0xFFE3F8FFL, 0xFFCEF0FFL, 0xFFFFDF9EL, 0xFFF2CC8FL, 0xFFE5BA73L, 0xFFFF7F50L, 0xFFFFD8C2L, 0xFF00B4D8L, 0xFFFF6B6BL),
        RoomTheme("royal", "Замок короля", "🏰", 150, 0xFF3B1F47L, 0xFF4B285CL, 0xFF7A4E2DL, 0xFF6B3E26L, 0xFF59321EL, 0xFFD62828L, 0xFFFFD166L, 0xFFC1121FL, 0xFF9B2226L)
    )

    fun getById(id: String): RoomTheme = themes.find { it.id == id } ?: themes.first()
}

data class RoomDecor(
    val id: String,
    val name: String,
    val emoji: String,
    val price: Int,
    val desc: String
)

object RoomDecorCatalog {
    val items = listOf(
        RoomDecor("plant", "Монстера", "🪴", 0, "Уютный цветок в горшке"),
        RoomDecor("aquarium", "Аквариум", "🐠", 85, "С живыми рыбками!"),
        RoomDecor("gaming_pc", "Игровой ПК", "🖥️", 130, "RGB-сетап для стримов"),
        RoomDecor("fireplace", "Камин", "🔥", 110, "С живым тёплым огнём"),
        RoomDecor("bookshelf", "Книжный шкаф", "📚", 75, "Уютная библиотека"),
        RoomDecor("teddy", "Гигантский мишка", "🧸", 95, "Плюшевый друг в углу")
    )
}

data class EquippedWardrobe(
    val head: String? = null,
    val face: String? = null,
    val torso: String? = null,
    val shoes: String? = null,
    val back: String? = null,
    val hand: String? = null,
    val aura: String? = null
)

data class PetSaveState(
    val uid: String = UUID.randomUUID().toString(),
    val petId: Int = 0,
    val paletteName: String? = null,
    val customName: String = "Котик",
    val stageName: String = PetStage.EGG.name,
    val eggTaps: Int = 0,
    val hunger: Int = 88,
    val mood: Int = 88,
    val energy: Int = 92,
    val hygiene: Int = 95,
    val health: Int = 100,
    val weight: Int = 5,
    val iq: Int = 10,
    val affection: Int = 15,
    // 4 навыка (Этап 2)
    val skillIntellect: Int = 1,
    val skillAgility: Int = 1,
    val skillCharisma: Int = 1,
    val skillCreativity: Int = 1,
    val coins: Int = 180,
    val ageMinutes: Int = 0,
    val generation: Int = 1,
    val poopCount: Int = 0,
    val isSleeping: Boolean = false,
    val isSick: Boolean = false,
    val isHibernating: Boolean = false,
    val isDeceased: Boolean = false,
    val deceasedAtMs: Long = 0L,
    val lastUpdatedMs: Long = System.currentTimeMillis(),
    val carryHunger: Float = 0f,
    val carryMood: Float = 0f,
    val carryEnergy: Float = 0f,
    val carryHygiene: Float = 0f,
    val carryHealth: Float = 0f,
    val carryPoopMinutes: Int = 0,
    val equippedHat: String? = null,
    val equippedFace: String? = null,
    val equippedTorso: String? = null,
    val equippedShoes: String? = null,
    val equippedBack: String? = null,
    val equippedHand: String? = null,
    val equippedAura: String? = null,
    val equippedAcc: String? = null,
    // Для обратной совместимости с v1
    val discoveredIds: List<Int>? = null,
    val unlockedPetIds: List<Int>? = null,
    val ownedItems: List<String>? = null,
    val ownedPalettes: List<String>? = null,
    val roomThemeId: String? = "sakura",
    val roomDecorId: String? = "plant",
    val highScoreCatch: Int = 0,
    val highScoreRunner: Int = 0,
    val highScoreSnake: Int = 0,
    val hasChosenStarter: Boolean = false
) {
    fun safeUid(): String = uid.ifBlank { "pet_$petId" }
    fun safePalette(pet: PetDefinition): PetPalette = PetPalette.byNameOrDefault(paletteName, pet.defaultPalette)
    fun safeRoomTheme(): String = roomThemeId ?: "sakura"
    fun safeRoomDecor(): String = roomDecorId ?: "plant"

    fun equipped(): EquippedWardrobe = EquippedWardrobe(
        head = equippedHat,
        face = equippedFace ?: equippedAcc?.takeIf { it.contains("shades") || it.contains("glasses") },
        torso = equippedTorso,
        shoes = equippedShoes,
        back = equippedBack ?: equippedAcc?.takeIf { it.contains("cape") },
        hand = equippedHand,
        aura = equippedAura
    )

    fun stage(): PetStage {
        return try {
            PetStage.valueOf(stageName)
        } catch (_: Throwable) {
            PetStage.BABY
        }
    }

    fun currentMood(action: PetAnimAction = PetAnimAction.NONE): PetMood {
        if (isDeceased) return PetMood.DECEASED
        if (isSick || health < 40) return PetMood.SICK
        if (isSleeping) return PetMood.SLEEPY
        return when (action) {
            PetAnimAction.PETTING, PetAnimAction.PRAISING -> PetMood.IN_LOVE
            PetAnimAction.DANCING, PetAnimAction.SINGING, PetAnimAction.PLAYING_TOY -> PetMood.PLAYFUL
            PetAnimAction.TICKLING -> PetMood.SHY
            PetAnimAction.HATCHED -> PetMood.ECSTATIC
            else -> when {
                hunger < 25 || hygiene < 25 || poopCount >= 2 -> PetMood.GRUMPY
                mood >= 85 && hunger >= 70 -> PetMood.ECSTATIC
                mood >= 65 -> PetMood.HAPPY
                energy < 30 -> PetMood.SLEEPY
                else -> PetMood.BORED
            }
        }
    }

    fun applyElapsedTime(nowMs: Long = System.currentTimeMillis()): PetSaveState {
        if (isHibernating || isDeceased) return copy(lastUpdatedMs = nowMs)
        val elapsedMinutes = ((nowMs - lastUpdatedMs) / 60_000L).toInt()
        if (elapsedMinutes <= 0) return this
        if (stage() == PetStage.EGG) return copy(lastUpdatedMs = nowMs)

        val steps = min(elapsedMinutes, 4320)
        var h = hunger
        var m = mood
        var e = energy
        var hy = hygiene
        var hp = health
        var poops = poopCount
        var sick = isSick
        var dead = isDeceased
        var deadAt = deceasedAtMs

        var ch = carryHunger
        var cm = carryMood
        var ce = carryEnergy
        var chy = carryHygiene
        var chp = carryHealth
        var poopMin = carryPoopMinutes + steps

        val sleeping = isSleeping
        ch += steps * (if (sleeping) 0.032f else 0.085f)
        cm += steps * (if (sleeping) 0.020f else 0.075f)
        chy += steps * (if (sleeping) 0.025f else 0.065f)

        val dH = floor(ch).toInt(); ch -= dH; h = max(0, h - dH)
        val dM = floor(cm).toInt(); cm -= dM; m = max(0, m - dM)
        val dHy = floor(chy).toInt(); chy -= dHy; hy = max(0, hy - dHy)

        if (sleeping) {
            ce += steps * 0.38f
            val gE = floor(ce).toInt(); ce -= gE; e = min(100, e + gE)
        } else {
            ce += steps * 0.080f
            val dE = floor(ce).toInt(); ce -= dE; e = max(0, e - dE)
        }

        if (!sleeping && poopMin >= 270) {
            val added = poopMin / 270
            poops = min(3, poops + added)
            poopMin %= 270
        }

        val criticalNeglect = (h <= 10 || hy <= 10 || poops >= 3)
        if (criticalNeglect || sick) {
            val hpRate = if (sick && criticalNeglect) 0.095f else if (criticalNeglect) 0.065f else 0.045f
            chp += steps * hpRate
            val dHp = floor(chp).toInt(); chp -= dHp; hp = max(0, hp - dHp)
            if (hp < 45 && criticalNeglect) sick = true
        } else if (h >= 50 && hy >= 50 && hp < 100) {
            chp += steps * 0.05f
            val gHp = floor(chp).toInt(); chp -= gHp; hp = min(100, hp + gHp)
        }

        if (hp <= 0) {
            hp = 0
            dead = true
            deadAt = nowMs
        }

        val newAge = ageMinutes + steps
        val newStage = computeStage(stage(), newAge)
        return copy(
            stageName = newStage.name,
            hunger = h,
            mood = m,
            energy = e,
            hygiene = hy,
            health = hp,
            ageMinutes = newAge,
            poopCount = poops,
            isSick = sick,
            isDeceased = dead,
            deceasedAtMs = deadAt,
            isSleeping = if (e >= 99 && sleeping) false else sleeping,
            carryHunger = ch,
            carryMood = cm,
            carryEnergy = ce,
            carryHygiene = chy,
            carryHealth = chp,
            carryPoopMinutes = poopMin,
            lastUpdatedMs = nowMs
        )
    }

    companion object {
        const val REVIVE_COST = 200

        fun computeStage(current: PetStage, ageMinutes: Int): PetStage {
            if (current == PetStage.EGG) return PetStage.EGG
            return when {
                ageMinutes >= PetStage.ADULT.minAgeMinutes -> PetStage.ADULT
                ageMinutes >= PetStage.TEEN.minAgeMinutes -> PetStage.TEEN
                ageMinutes >= PetStage.CHILD.minAgeMinutes -> PetStage.CHILD
                else -> PetStage.BABY
            }
        }
    }
}

// =====================================================================
// ЭТАП 3: ПОГОДА ЗА ОКНОМ, ЕЖЕДНЕВНЫЙ БОНУС, ДОСТИЖЕНИЯ И ФОТОАЛЬБОМ
// =====================================================================

enum class WeatherType(val title: String, val emoji: String) {
    SUNNY("Ясно", "☀️"),
    CLOUDY("Облачно", "⛅"),
    RAIN("Дождик", "🌧️"),
    STORM("Гроза", "⛈️"),
    SNOW("Снегопад", "❄️"),
    STARFALL("Звездопад", "🌠");

    companion object {
        fun resolve(mode: String, hour: Int, nowMs: Long = System.currentTimeMillis()): WeatherType {
            entries.find { it.name == mode }?.let { return it }
            // Авто-погода меняется каждые 2 часа
            val bucket = ((nowMs / 7_200_000L) % 6L).toInt()
            val isNight = hour >= 20 || hour < 6
            return when (bucket) {
                0 -> if (isNight) STARFALL else SUNNY
                1 -> CLOUDY
                2 -> RAIN
                3 -> if (isNight) STARFALL else SUNNY
                4 -> SNOW
                else -> if (isNight) STORM else CLOUDY
            }
        }
    }
}

data class DailyRewardDef(
    val day: Int,
    val coins: Int,
    val collectibleId: String?,
    val freeLegendRoll: Boolean = false
)

object DailyRewardCatalog {
    val rewards = listOf(
        DailyRewardDef(1, 35, "clover"),
        DailyRewardDef(2, 55, "bookmark"),
        DailyRewardDef(3, 80, "latte"),
        DailyRewardDef(4, 110, "popcorn"),
        DailyRewardDef(5, 140, "sakura_branch"),
        DailyRewardDef(6, 180, "neon_chip"),
        DailyRewardDef(7, 260, "moon_crystal", freeLegendRoll = true)
    )
}

data class AchievementDef(
    val id: String,
    val title: String,
    val desc: String,
    val emoji: String,
    val rewardCoins: Int
)

object AchievementCatalog {
    val all = listOf(
        AchievementDef("ach_hatch", "Первый шаг!", "Вылупить своего первого питомца из яйца", "🐣", 30),
        AchievementDef("ach_adult", "Совсем большой", "Вырастить питомца до взрослой стадии", "👑", 75),
        AchievementDef("ach_collection10", "Юный коллекционер", "Открыть 16+ видов в Альманахе", "🐾", 60),
        AchievementDef("ach_fashion", "Икона стиля", "Собрать 5+ вещей в гардеробе", "👗", 55),
        AchievementDef("ach_arcade", "Король Аркады", "Набрать 80+ очков в Ловле, Раннере или Змейке", "🕹️", 70),
        AchievementDef("ach_scholar", "Отличник", "Прокачать сумму навыков до 20+", "🎓", 65),
        AchievementDef("ach_astro", "Покоритель звёзд", "Открыть доступ к профессии Космонавта", "🚀", 120),
        AchievementDef("ach_travel", "Вокруг света", "Собрать все 4 открытки путешествий", "✈️", 110),
        AchievementDef("ach_craft", "Мастер-крафтер", "Скрафтить вещь в Мастерской", "🛠️", 60),
        AchievementDef("ach_hotel", "Уютный отель", "Поселить 2+ питомцев в Отеле", "🏨", 50),
        AchievementDef("ach_love", "Лучшие друзья", "Достичь 50+ привязанности с питомцем", "💖", 80),
        AchievementDef("ach_butterfly", "Ловец удачи", "Поймать летающую бабочку или звёздочку в комнате", "🦋", 45)
    )

    fun evaluateNew(account: AccountSaveState): List<AchievementDef> {
        val unlocked = account.safeAchievements().toSet()
        val pet = account.activePet()
        val skillSum = pet.skillIntellect + pet.skillAgility + pet.skillCharisma + pet.skillCreativity
        val wardrobeOwnedCount = account.safeOwned().count { it.startsWith("w_") }
        val maxArcade = maxOf(account.highScoreCatch, account.highScoreRunner, account.highScoreSnake)

        return all.filter { ach ->
            if (ach.id in unlocked) return@filter false
            when (ach.id) {
                "ach_hatch" -> account.myPets.any { it.stage() != PetStage.EGG }
                "ach_adult" -> account.myPets.any { it.stage() == PetStage.ADULT }
                "ach_collection10" -> account.safeUnlockedPets().size >= 16
                "ach_fashion" -> wardrobeOwnedCount >= 5
                "ach_arcade" -> maxArcade >= 80
                "ach_scholar" -> skillSum >= 20
                "ach_astro" -> pet.skillIntellect >= 24 && pet.skillAgility >= 18 && pet.skillCharisma >= 10
                "ach_travel" -> account.safePostcards().size >= 4
                "ach_craft" -> account.craftedCount >= 1
                "ach_hotel" -> account.myPets.size >= 2
                "ach_love" -> account.myPets.any { it.affection >= 50 }
                "ach_butterfly" -> account.bonusCaughtCount >= 1
                else -> false
            }
        }
    }
}

data class PhotoMemory(
    val id: String = UUID.randomUUID().toString(),
    val petId: Int,
    val paletteName: String?,
    val petName: String,
    val stageName: String,
    val caption: String,
    val locationLabel: String,
    val weatherLabel: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val equipped: EquippedWardrobe = EquippedWardrobe()
)

data class AccountSaveState(
    val coins: Int = 220,
    val unlockedPetIds: List<Int> = (0..14).toList(),
    val ownedItems: List<String> = listOf("sakura", "plant"),
    val ownedPalettes: List<String> = listOf(PetPalette.SAKURA.name, PetPalette.MINT.name),
    val roomThemeId: String = "sakura",
    val roomDecorId: String = "plant",
    val myPets: List<PetSaveState> = emptyList(),
    val activePetUid: String = "",
    val memorialPets: List<PetSaveState> = emptyList(),
    val collectibles: Map<String, Int>? = null,
    val postcards: List<String>? = null,
    val soundVolume: Float = 0.75f,
    val soundMuted: Boolean = false,
    val hapticsEnabled: Boolean = true,
    // Этап 3: Погода, уведомления, бонус дня, ачивки, фотоальбом
    val weatherMode: String = "AUTO",
    val notificationsEnabled: Boolean = true,
    val quietHoursEnabled: Boolean = true,
    val lastDailyClaimDay: Long = 0L,
    val dailyStreak: Int = 0,
    val unlockedAchievements: List<String>? = null,
    val photoAlbum: List<PhotoMemory>? = null,
    val bonusCaughtCount: Int = 0,
    val craftedCount: Int = 0,
    val highScoreCatch: Int = 0,
    val highScoreRunner: Int = 0,
    val highScoreSnake: Int = 0,
    val hasChosenStarter: Boolean = false
) {
    fun safeCollectibles(): Map<String, Int> = collectibles ?: emptyMap()
    fun safePostcards(): List<String> = postcards ?: emptyList()
    fun safeAchievements(): List<String> = unlockedAchievements ?: emptyList()
    fun safePhotos(): List<PhotoMemory> = photoAlbum ?: emptyList()

    fun currentEpochDay(nowMs: Long = System.currentTimeMillis()): Long = nowMs / 86_400_000L

    fun canClaimDaily(nowMs: Long = System.currentTimeMillis()): Boolean =
        currentEpochDay(nowMs) > lastDailyClaimDay

    fun nextDailyDayIndex(nowMs: Long = System.currentTimeMillis()): Int {
        val today = currentEpochDay(nowMs)
        val continuous = (lastDailyClaimDay == 0L) || (today - lastDailyClaimDay <= 1L)
        val nextStreak = if (continuous) (dailyStreak % 7) + 1 else 1
        return nextStreak
    }

    fun addPhoto(caption: String, locationLabel: String, weatherLabel: String): AccountSaveState {
        val p = activePet()
        val mem = PhotoMemory(
            petId = p.petId,
            paletteName = p.paletteName,
            petName = p.customName,
            stageName = p.stageName,
            caption = caption,
            locationLabel = locationLabel,
            weatherLabel = weatherLabel,
            equipped = p.equipped()
        )
        return copy(photoAlbum = (listOf(mem) + safePhotos()).take(40))
    }

    fun addCollectible(itemId: String, amount: Int = 1): AccountSaveState {
        val cur = safeCollectibles().toMutableMap()
        cur[itemId] = (cur[itemId] ?: 0) + amount
        return copy(collectibles = cur)
    }

    fun consumeCollectibles(cost: Map<String, Int>): AccountSaveState? {
        val cur = safeCollectibles().toMutableMap()
        for ((k, v) in cost) {
            if ((cur[k] ?: 0) < v) return null
        }
        for ((k, v) in cost) {
            val left = (cur[k] ?: 0) - v
            if (left <= 0) cur.remove(k) else cur[k] = left
        }
        return copy(collectibles = cur)
    }

    fun safeUnlockedPets(): List<Int> {
        val ownedUids = myPets.map { it.petId }
        return (unlockedPetIds + (0..14).toList() + ownedUids).distinct()
    }

    fun safeOwned(): List<String> = (ownedItems + listOf("sakura", "plant")).distinct()

    fun safeOwnedPalettes(): List<String> {
        val activePal = activePet().paletteName
        return (ownedPalettes + listOf(PetPalette.SAKURA.name, PetPalette.MINT.name) + listOfNotNull(activePal)).distinct()
    }

    fun activePet(): PetSaveState {
        if (myPets.isEmpty()) return PetSaveState()
        return myPets.find { it.uid == activePetUid } ?: myPets.first()
    }

    fun updateActivePet(transform: (PetSaveState) -> PetSaveState): AccountSaveState {
        val now = System.currentTimeMillis()
        if (myPets.isEmpty()) {
            val fresh = transform(PetSaveState()).copy(isHibernating = false, lastUpdatedMs = now)
            return copy(myPets = listOf(fresh), activePetUid = fresh.uid)
        }
        val targetUid = activePet().uid
        val updated = myPets.map { p ->
            if (p.uid == targetUid) transform(p).copy(isHibernating = false, lastUpdatedMs = now)
            else p.copy(isHibernating = true)
        }
        return copy(myPets = updated, activePetUid = targetUid)
    }

    fun switchToPet(targetUid: String): AccountSaveState {
        val now = System.currentTimeMillis()
        if (myPets.none { it.uid == targetUid }) return this
        val updated = myPets.map { p ->
            if (p.uid == targetUid) p.copy(isHibernating = false, lastUpdatedMs = now)
            else p.copy(isHibernating = true, lastUpdatedMs = now)
        }
        return copy(myPets = updated, activePetUid = targetUid)
    }

    fun addPetAndActivate(
        petDef: PetDefinition,
        palette: PetPalette,
        asEgg: Boolean = true,
        nextGen: Int = 1
    ): AccountSaveState {
        val now = System.currentTimeMillis()
        val hibernatedExisting = myPets.map { it.copy(isHibernating = true, lastUpdatedMs = now) }
        val newPet = PetSaveState(
            uid = UUID.randomUUID().toString(),
            petId = petDef.id,
            paletteName = palette.name,
            customName = petDef.name,
            stageName = if (asEgg) PetStage.EGG.name else PetStage.BABY.name,
            eggTaps = 0,
            hunger = 92,
            mood = 92,
            energy = 95,
            hygiene = 100,
            health = 100,
            generation = nextGen,
            isHibernating = false,
            lastUpdatedMs = now,
            hasChosenStarter = true
        )
        return copy(
            myPets = hibernatedExisting + newPet,
            activePetUid = newPet.uid,
            unlockedPetIds = (safeUnlockedPets() + petDef.id).distinct(),
            ownedPalettes = (safeOwnedPalettes() + palette.name).distinct(),
            hasChosenStarter = true
        )
    }

    fun retireActiveToMemorial(): AccountSaveState {
        val cur = activePet()
        val remaining = myPets.filter { it.uid != cur.uid }
        val nextActive = remaining.firstOrNull()?.copy(isHibernating = false, lastUpdatedMs = System.currentTimeMillis())
        val updatedRemaining = if (nextActive != null) {
            remaining.map { if (it.uid == nextActive.uid) nextActive else it }
        } else emptyList()
        return copy(
            myPets = updatedRemaining,
            activePetUid = nextActive?.uid ?: "",
            memorialPets = (memorialPets + cur.copy(isHibernating = true)).takeLast(30),
            hasChosenStarter = updatedRemaining.isNotEmpty()
        )
    }
}

object PetStorage {
    private const val PREF_NAME = "tamaverse_save_v1"
    private const val KEY_ACCOUNT = "account_state_json_v2"
    private const val KEY_LEGACY_STATE = "pet_state_json"
    private val gson = Gson()

    fun exportBackupCode(account: AccountSaveState): String {
        val json = gson.toJson(account)
        return Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    fun importBackupCode(code: String): AccountSaveState? {
        return try {
            val decoded = Base64.decode(code.trim(), Base64.DEFAULT)
            val json = String(decoded, Charsets.UTF_8)
            val parsed = gson.fromJson(json, AccountSaveState::class.java)
            if (parsed != null && parsed.myPets.isNotEmpty()) parsed else null
        } catch (_: Throwable) {
            null
        }
    }

    fun save(context: Context, account: AccountSaveState) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_ACCOUNT, gson.toJson(account)).apply()
        } catch (_: Throwable) {
        }
    }

    fun load(context: Context): AccountSaveState {
        return try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val jsonV2 = prefs.getString(KEY_ACCOUNT, null)
            if (!jsonV2.isNullOrBlank()) {
                val loaded = gson.fromJson(jsonV2, AccountSaveState::class.java)
                val now = System.currentTimeMillis()
                val updatedPets = loaded.myPets.map { p ->
                    if (p.uid == loaded.activePetUid) p.copy(isHibernating = false).applyElapsedTime(now)
                    else p.copy(isHibernating = true, lastUpdatedMs = now)
                }
                return loaded.copy(myPets = updatedPets)
            }

            val legacyJson = prefs.getString(KEY_LEGACY_STATE, null)
            if (!legacyJson.isNullOrBlank()) {
                val old = gson.fromJson(legacyJson, PetSaveState::class.java)
                val fixedOld = old.copy(
                    uid = old.uid.ifBlank { UUID.randomUUID().toString() },
                    isHibernating = false,
                    hasChosenStarter = old.hasChosenStarter || old.stage() != PetStage.EGG
                ).applyElapsedTime()
                val migrated = AccountSaveState(
                    coins = old.coins,
                    unlockedPetIds = ((old.unlockedPetIds ?: emptyList()) + (0..14).toList() + old.petId).distinct(),
                    ownedItems = ((old.ownedItems ?: emptyList()) + listOf("sakura", "plant")).distinct(),
                    ownedPalettes = ((old.ownedPalettes ?: emptyList()) + listOf(PetPalette.SAKURA.name, PetPalette.MINT.name) + listOfNotNull(old.paletteName)).distinct(),
                    roomThemeId = old.safeRoomTheme(),
                    roomDecorId = old.safeRoomDecor(),
                    myPets = listOf(fixedOld),
                    activePetUid = fixedOld.uid,
                    highScoreCatch = old.highScoreCatch,
                    highScoreRunner = old.highScoreRunner,
                    highScoreSnake = old.highScoreSnake,
                    hasChosenStarter = fixedOld.hasChosenStarter
                )
                save(context, migrated)
                return migrated
            }

            AccountSaveState(hasChosenStarter = false)
        } catch (_: Throwable) {
            AccountSaveState(hasChosenStarter = false)
        }
    }
}
