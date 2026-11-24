package com.tamaverse.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TamaNotificationHelper.ensureChannel(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TamaVerseApp()
            }
        }
    }
}

private enum class ActiveModal {
    NONE, CATALOG, GACHA, STARTER, PROFILE, MY_PETS, CITY_BAG, SETTINGS, DAILY_BONUS, ALBUM_ACH
}

private data class CityHubTab(val title: String, val emoji: String, val scene: RoomSceneType)

@Composable
fun TamaVerseApp() {
    val context = LocalContext.current
    var account by remember {
        mutableStateOf(PetStorage.load(context).also { loaded ->
            SoundEngine.volume = loaded.soundVolume
            SoundEngine.muted = loaded.soundMuted
            SoundEngine.hapticsEnabled = loaded.hapticsEnabled
        })
    }
    val state = account.activePet()
    val pet = remember(state.petId) { PetCatalog.getById(state.petId) }
    val palette = remember(state.paletteName, pet.id) { state.safePalette(pet) }

    var animFrame by remember { mutableIntStateOf(0) }
    var actionAnim by remember { mutableStateOf(PetAnimAction.NONE) }
    var toastBubble by remember { mutableStateOf<String?>(null) }
    var currentRoom by remember { mutableStateOf(RoomSceneType.BEDROOM) }
    var lastFedFoodId by remember { mutableStateOf(pet.favoriteFoodId) }
    var activeTravelId by remember { mutableStateOf("japan") }
    var bonusButterflyActive by remember { mutableStateOf(false) }
    var modal by remember {
        mutableStateOf(
            when {
                !account.hasChosenStarter -> ActiveModal.STARTER
                account.canClaimDaily() -> ActiveModal.DAILY_BONUS
                else -> ActiveModal.NONE
            }
        )
    }
    var activeGame by remember { mutableStateOf(MiniGameType.NONE) }

    val hourNow = remember(animFrame / 300) { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val currentWeather = remember(account.weatherMode, hourNow, animFrame / 300) {
        WeatherType.resolve(account.weatherMode, hourNow)
    }

    fun updateAccount(transform: (AccountSaveState) -> AccountSaveState) {
        var next = transform(account)
        val newAchs = AchievementCatalog.evaluateNew(next)
        if (newAchs.isNotEmpty()) {
            val bonusCoins = newAchs.sumOf { it.rewardCoins }
            next = next.copy(
                coins = next.coins + bonusCoins,
                unlockedAchievements = (next.safeAchievements() + newAchs.map { it.id }).distinct()
            )
            val first = newAchs.first()
            toastBubble = "🏆 Ачивка: ${first.title} (+$bonusCoins 🪙)!"
        }
        account = next
        PetStorage.save(context, next)
        TamaNotificationHelper.scheduleCareReminders(context, next)
    }

    fun updateActivePet(transform: (PetSaveState) -> PetSaveState) {
        updateAccount { it.updateActivePet(transform) }
    }

    fun feedbackClick() {
        SoundEngine.playClick()
        SoundEngine.vibrate(context, 14L)
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            animFrame = (animFrame + 1) % 1000
        }
    }

    // Летающая золотая бабочка в спальне каждые ~45 сек
    LaunchedEffect(Unit) {
        while (true) {
            delay(45_000L)
            if (currentRoom == RoomSceneType.BEDROOM && !account.activePet().isSleeping && !account.activePet().isDeceased) {
                bonusButterflyActive = true
                delay(12_000L)
                bonusButterflyActive = false
            }
        }
    }

    LaunchedEffect(actionAnim, toastBubble) {
        if (actionAnim != PetAnimAction.NONE || toastBubble != null) {
            delay(2500)
            actionAnim = PetAnimAction.NONE
            toastBubble = null
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            updateActivePet { cur ->
                cur.applyElapsedTime(System.currentTimeMillis())
            }
        }
    }

    val currentMood = state.currentMood(actionAnim)
    val autoBubble = toastBubble ?: when {
        state.isDeceased -> "Питомец стал ангелочком... Можно возродить за ${PetSaveState.REVIVE_COST} 🪙"
        state.stage() == PetStage.EGG -> "Нажми на яйцо! (${state.eggTaps}/5)"
        state.isSleeping -> "Сладких снов в кроватке... Zzz"
        state.isSick -> "Мне нездоровится... Нужен доктор!"
        state.poopCount > 0 -> "Фу! Убери в спальне!"
        state.hunger < 25 -> "Очень хочу кушать!"
        state.energy < 20 -> "Глазки слипаются... Пора спать!"
        state.mood < 30 -> "Мне скучно! Поиграй со мной!"
        currentWeather == WeatherType.RAIN && currentRoom == RoomSceneType.BEDROOM -> "За окном дождик... Уютно дома ☕"
        currentWeather == WeatherType.SNOW && currentRoom == RoomSceneType.BEDROOM -> "Снежинки кружат за окном! ❄️"
        currentWeather == WeatherType.STARFALL && currentRoom == RoomSceneType.BEDROOM -> "Звездопад! Загадай желание 🌠"
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1233), Color(0xFF130D26))))
            .systemBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // --- ВЕРХНЯЯ ПАНЕЛЬ ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { feedbackClick(); modal = ActiveModal.PROFILE }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = state.customName,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("📋", fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BadgeChip(state.stage().title, Color(0xFF6C5CE7))
                    BadgeChip("${currentMood.emoji} ${currentMood.title}", Color(0xFF2D2152))
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFF00B894), Color(0xFF0984E3))))
                        .clickable { feedbackClick(); modal = ActiveModal.MY_PETS }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🐾 ${account.myPets.size.coerceAtLeast(1)}", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                }
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xFFE84393), Color(0xFFFF9F43))))
                        .clickable { feedbackClick(); modal = ActiveModal.GACHA }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🎰", color = Color.White, fontSize = 13.sp)
                }
                Surface(shape = RoundedCornerShape(11.dp), color = Color(0xFF2D2152)) {
                    Text(
                        text = "🪙${account.coins}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                        color = Color(0xFFFFD166),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color(0xFF2D2152))
                        .clickable { feedbackClick(); modal = ActiveModal.SETTINGS },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "Настройки",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // --- 5 ПОЛОСОК СТАТОВ ---
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            StatBar("Сыт.", state.hunger, Color(0xFFFF9F43), Modifier.weight(1f))
            StatBar("Счаст.", state.mood, Color(0xFFFF6B9D), Modifier.weight(1f))
            StatBar("Энерг.", state.energy, Color(0xFF54A0FF), Modifier.weight(1f))
            StatBar("Чист.", state.hygiene, Color(0xFF1DD1A1), Modifier.weight(1f))
            StatBar("Здор.", state.health, Color(0xFFEE5253), Modifier.weight(1f))
        }

        // --- СМЕНЯЕМЫЕ КОМНАТЫ И ЛОКАЦИИ С ПЛАВНЫМ ПЕРЕХОДОМ ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .border(2.5.dp, Color(0xFF533E8F), RoundedCornerShape(22.dp))
        ) {
            AnimatedContent(
                targetState = currentRoom,
                transitionSpec = {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                },
                label = "room_transition"
            ) { room ->
                PixelRoomStage(
                    account = account,
                    state = state,
                    pet = pet,
                    scene = room,
                    frame = animFrame,
                    action = actionAnim,
                    speechBubble = autoBubble,
                    lastFedFoodId = lastFedFoodId,
                    activeTravelId = activeTravelId,
                    weather = currentWeather,
                    bonusButterflyActive = bonusButterflyActive,
                    onBonusTap = {
                        bonusButterflyActive = false
                        SoundEngine.playHatch()
                        SoundEngine.vibrate(context, 30L)
                        actionAnim = PetAnimAction.HATCHED
                        toastBubble = "Поймал бабочку удачи! +15 🪙 🦋"
                        updateAccount { acc ->
                            acc.copy(
                                coins = acc.coins + 15,
                                bonusCaughtCount = acc.bonusCaughtCount + 1
                            )
                        }
                    },
                    onRoomTap = {
                        when {
                            state.isDeceased -> {
                                SoundEngine.playSleep()
                                toastBubble = "Нажми «Возродить», чтобы вернуть ${state.customName}!"
                            }
                            state.stage() == PetStage.EGG -> {
                                val nextTaps = state.eggTaps + 1
                                if (nextTaps >= 5) {
                                    SoundEngine.playHatch()
                                    SoundEngine.vibrate(context, 40L)
                                    actionAnim = PetAnimAction.HATCHED
                                    toastBubble = "Ура! Вылупился ${state.customName}! ★"
                                    updateAccount { acc ->
                                        acc.copy(coins = acc.coins + 30).updateActivePet {
                                            it.copy(stageName = PetStage.BABY.name, eggTaps = 0)
                                        }
                                    }
                                } else {
                                    feedbackClick()
                                    updateActivePet { it.copy(eggTaps = nextTaps) }
                                }
                            }
                            state.isSleeping -> {
                                feedbackClick()
                                toastBubble = "Тсс... ${state.customName} сладко спит!"
                            }
                            else -> {
                                SoundEngine.playHappy()
                                SoundEngine.vibrate(context, 16L)
                                actionAnim = PetAnimAction.PETTING
                                toastBubble = listOf(
                                    pet.pettingReaction,
                                    "${state.customName} довольно мурчит~ ♥",
                                    "Так приятно и тепло! ★"
                                ).random()
                                updateAccount { acc ->
                                    acc.copy(coins = acc.coins + 2).updateActivePet {
                                        it.copy(
                                            mood = (it.mood + 6).coerceAtMost(100),
                                            affection = it.affection + 2
                                        )
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(shape = RoundedCornerShape(10.dp), color = Color(0xBB1A1233)) {
                    Text(
                        text = "${currentRoom.emoji} ${currentRoom.title}",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
                if (currentRoom != RoomSceneType.BEDROOM) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFE84393),
                        modifier = Modifier.clickable { feedbackClick(); currentRoom = RoomSceneType.BEDROOM }
                    ) {
                        Text(
                            text = "🏠 В спальню",
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Погода + Камера + Ачивки внизу справа
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xBB1A1233),
                    modifier = Modifier.clickable {
                        feedbackClick()
                        toastBubble = "Погода: ${currentWeather.emoji} ${currentWeather.title}"
                    }
                ) {
                    Text(
                        text = currentWeather.emoji,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 12.sp
                    )
                }
                if (state.stage() != PetStage.EGG) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xBB6C5CE7),
                        modifier = Modifier.clickable {
                            SoundEngine.playHatch()
                            SoundEngine.vibrate(context, 18L)
                            updateAccount {
                                it.addPhoto(
                                    caption = "${state.customName} — ${currentMood.title}",
                                    locationLabel = "${currentRoom.emoji} ${currentRoom.title}",
                                    weatherLabel = "${currentWeather.emoji} ${currentWeather.title}"
                                )
                            }
                            toastBubble = "📸 Фото сохранено в Альбом!"
                        }
                    ) {
                        Text("📸", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xBBFFB703),
                    modifier = Modifier.clickable { feedbackClick(); modal = ActiveModal.ALBUM_ACH }
                ) {
                    Text("🏆", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp)
                }
                if (account.canClaimDaily()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFE84393),
                        modifier = Modifier.clickable { feedbackClick(); modal = ActiveModal.DAILY_BONUS }
                    ) {
                        Text("🎁", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp)
                    }
                }
            }
        }

        // --- КОНТЕКСТНАЯ ПАНЕЛЬ ---
        when {
            state.isDeceased -> {
                DeceasedControlBar(
                    petName = state.customName,
                    coins = account.coins,
                    onRevive = {
                        if (account.coins < PetSaveState.REVIVE_COST) {
                            toastBubble = "Нужно ${PetSaveState.REVIVE_COST} 🪙! Переключись на другого или сыграй в Игры!"
                        } else {
                            SoundEngine.playHatch()
                            SoundEngine.vibrate(context, 45L)
                            actionAnim = PetAnimAction.HATCHED
                            toastBubble = "${state.customName} снова с тобой! ♥"
                            updateAccount { acc ->
                                acc.copy(coins = acc.coins - PetSaveState.REVIVE_COST).updateActivePet {
                                    it.copy(
                                        isDeceased = false,
                                        isSick = false,
                                        health = 85,
                                        hunger = 75,
                                        mood = 80,
                                        energy = 80,
                                        hygiene = 85,
                                        poopCount = 0
                                    )
                                }
                            }
                        }
                    },
                    onRetire = {
                        SoundEngine.playSleep()
                        var becameEmpty = false
                        updateAccount { acc ->
                            val next = acc.retireActiveToMemorial()
                            becameEmpty = next.myPets.isEmpty()
                            next
                        }
                        if (becameEmpty) modal = ActiveModal.STARTER
                    }
                )
            }

            currentRoom == RoomSceneType.BEDROOM -> {
                if (state.stage() != PetStage.EGG && !state.isSleeping) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        MiniInteractChip("🤗 Погладить") {
                            SoundEngine.playHappy(); SoundEngine.vibrate(context, 16L); actionAnim = PetAnimAction.PETTING
                            toastBubble = listOf(pet.pettingReaction, "${state.customName} ластится~ ♥").random()
                            updateActivePet { it.copy(mood = (it.mood + 7).coerceAtMost(100), affection = it.affection + 2) }
                        }
                        MiniInteractChip("🤭 Пощекотать") {
                            feedbackClick(); actionAnim = PetAnimAction.TICKLING
                            toastBubble = "Хи-хи-хи! Щекотно!"
                            updateActivePet { it.copy(mood = (it.mood + 8).coerceAtMost(100), energy = (it.energy - 1).coerceAtLeast(5)) }
                        }
                        MiniInteractChip("💃 Танцевать") {
                            SoundEngine.playHappy(); actionAnim = PetAnimAction.DANCING
                            toastBubble = "Танцуем вместе! ♪"
                            updateAccount { acc ->
                                acc.copy(coins = acc.coins + 2).updateActivePet {
                                    it.copy(mood = (it.mood + 10).coerceAtMost(100), energy = (it.energy - 2).coerceAtLeast(5))
                                }
                            }
                        }
                        MiniInteractChip("🎒 Рюкзак") {
                            feedbackClick()
                            modal = ActiveModal.CITY_BAG
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModernArcadeButton("Кухня", Icons.Rounded.Restaurant, Color(0xFFFF9F43), Color(0xFFE65C00), Modifier.weight(1f)) {
                            feedbackClick()
                            if (state.stage() == PetStage.EGG) toastBubble = "Сначала вылупи яйцо!"
                            else currentRoom = RoomSceneType.KITCHEN
                        }
                        ModernArcadeButton("Ванная", Icons.Rounded.Bathtub, Color(0xFF1DD1A1), Color(0xFF008F6B), Modifier.weight(1f)) {
                            feedbackClick()
                            if (state.stage() == PetStage.EGG) toastBubble = "Сначала вылупи яйцо!"
                            else currentRoom = RoomSceneType.BATHROOM
                        }
                        ModernArcadeButton(
                            if (state.isSleeping) "Подъём" else "Сон",
                            if (state.isSleeping) Icons.Rounded.WbSunny else Icons.Rounded.Bedtime,
                            Color(0xFF7B61FF),
                            Color(0xFF4328B8),
                            Modifier.weight(1f)
                        ) {
                            if (state.stage() == PetStage.EGG) toastBubble = "Яйцу спать не нужно!"
                            else {
                                SoundEngine.playSleep()
                                updateActivePet { it.copy(isSleeping = !it.isSleeping) }
                            }
                        }
                        ModernArcadeButton("Лечить", Icons.Rounded.MedicalServices, Color(0xFFFF6B6B), Color(0xFFC02942), Modifier.weight(1f)) {
                            if (state.stage() == PetStage.EGG) toastBubble = "Яйцо здорово!"
                            else {
                                SoundEngine.playHeal()
                                actionAnim = PetAnimAction.HEALING
                                toastBubble = "Питомец здоров! ♥"
                                updateActivePet { it.copy(isSick = false, health = (it.health + 35).coerceAtMost(100)) }
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModernArcadeButton("Город", Icons.Rounded.LocationCity, Color(0xFF00B4D8), Color(0xFF0077B6), Modifier.weight(1f)) {
                            feedbackClick()
                            if (state.stage() == PetStage.EGG) toastBubble = "Сначала вылупи яйцо!"
                            else if (state.isSleeping) toastBubble = "Разбуди питомца перед выходом в город!"
                            else currentRoom = RoomSceneType.CITY_SCHOOL
                        }
                        ModernArcadeButton("Игры (8)", Icons.Rounded.SportsEsports, Color(0xFF9B7BFF), Color(0xFF5D3FD3), Modifier.weight(1f)) {
                            feedbackClick()
                            if (state.stage() == PetStage.EGG) toastBubble = "Сначала вылупи яйцо!"
                            else if (state.isSleeping) toastBubble = "Разбуди питомца для игр!"
                            else currentRoom = RoomSceneType.ARCADE
                        }
                        ModernArcadeButton("Бутик", Icons.Rounded.Checkroom, Color(0xFFFFB703), Color(0xFFCC7A00), Modifier.weight(1f)) {
                            feedbackClick()
                            currentRoom = RoomSceneType.BOUTIQUE
                        }
                        ModernArcadeButton("Альманах", Icons.Rounded.Pets, Color(0xFFFF5E99), Color(0xFFB81D65), Modifier.weight(1f)) {
                            feedbackClick()
                            modal = ActiveModal.CATALOG
                        }
                    }
                }
            }

            currentRoom == RoomSceneType.KITCHEN -> KitchenRoomPanel(
                coins = account.coins,
                favoriteFoodId = pet.favoriteFoodId,
                onBackHome = { currentRoom = RoomSceneType.BEDROOM },
                onFeed = { food ->
                    if (state.isSleeping) toastBubble = "Сначала разбуди питомца!"
                    else if (account.coins < food.price) toastBubble = "Не хватает монет!"
                    else {
                        lastFedFoodId = food.id
                        val isFav = food.id == pet.favoriteFoodId
                        SoundEngine.playEat()
                        SoundEngine.vibrate(context, 18L)
                        actionAnim = PetAnimAction.EATING
                        toastBubble = if (isFav) "ОБОЖАЮ ${food.name}!! ${food.emoji} (+Бонус ♥)" else "Ням-ням! ${food.name} ${food.emoji}"
                        val extraMood = if (isFav) 12 else 0
                        updateAccount { acc ->
                            acc.copy(coins = acc.coins - food.price).updateActivePet {
                                it.copy(
                                    hunger = (it.hunger + food.hunger).coerceAtMost(100),
                                    mood = (it.mood + food.mood + extraMood).coerceAtMost(100),
                                    weight = (it.weight + food.weight).coerceAtMost(99),
                                    affection = it.affection + if (isFav) 4 else 1
                                )
                            }
                        }
                    }
                }
            )

            currentRoom == RoomSceneType.BATHROOM -> BathroomRoomPanel(
                poopCount = state.poopCount,
                onBath = {
                    SoundEngine.playBath()
                    SoundEngine.vibrate(context, 20L)
                    actionAnim = PetAnimAction.BATHING
                    toastBubble = "Ванночка с пеной и уточкой! 🛁🫧"
                    updateActivePet { it.copy(hygiene = 100, mood = (it.mood + 14).coerceAtMost(100)) }
                },
                onCleanRoom = {
                    SoundEngine.playBath()
                    actionAnim = PetAnimAction.PETTING
                    val bonus = state.poopCount * 6
                    toastBubble = if (state.poopCount > 0) "Убрано! +$bonus 🪙" else "Комната и так блестит! ★"
                    updateAccount { acc ->
                        acc.copy(coins = acc.coins + bonus).updateActivePet {
                            it.copy(poopCount = 0, hygiene = (it.hygiene + 25).coerceAtMost(100))
                        }
                    }
                },
                onBackHome = { currentRoom = RoomSceneType.BEDROOM }
            )

            currentRoom == RoomSceneType.BOUTIQUE -> BoutiqueRoomPanel(
                account = account,
                state = state,
                pet = pet,
                onBackHome = { currentRoom = RoomSceneType.BEDROOM },
                onBuyOrEquipWardrobe = { item ->
                    val owned = item.id in account.safeOwned()
                    if (!owned && account.coins < item.price) {
                        toastBubble = "Не хватает монет! Сыграй в Аркаде!"
                    } else {
                        SoundEngine.playHappy()
                        toastBubble = if (owned) "Примеряем: ${item.name}!" else "Куплено: ${item.name}!"
                        updateAccount { acc ->
                            val cost = if (owned) 0 else item.price
                            acc.copy(
                                coins = acc.coins - cost,
                                ownedItems = (acc.safeOwned() + item.id).distinct()
                            ).updateActivePet { cur ->
                                fun toggle(curVal: String?): String? = if (curVal == item.id && owned) null else item.id
                                when (item.slot) {
                                    WardrobeSlot.HEAD -> cur.copy(equippedHat = toggle(cur.equippedHat))
                                    WardrobeSlot.FACE -> cur.copy(equippedFace = toggle(cur.equippedFace), equippedAcc = null)
                                    WardrobeSlot.TORSO -> cur.copy(equippedTorso = toggle(cur.equippedTorso))
                                    WardrobeSlot.SHOES -> cur.copy(equippedShoes = toggle(cur.equippedShoes))
                                    WardrobeSlot.BACK -> cur.copy(equippedBack = toggle(cur.equippedBack))
                                    WardrobeSlot.HAND -> cur.copy(equippedHand = toggle(cur.equippedHand))
                                    WardrobeSlot.AURA -> cur.copy(equippedAura = toggle(cur.equippedAura))
                                }
                            }
                        }
                    }
                },
                onBuyOrEquipPalette = { pal ->
                    val owned = pal.name in account.safeOwnedPalettes()
                    if (!owned && account.coins < pal.price) {
                        toastBubble = "Не хватает монет на краску!"
                    } else {
                        SoundEngine.playHappy()
                        updateAccount { acc ->
                            val cost = if (owned) 0 else pal.price
                            acc.copy(
                                coins = acc.coins - cost,
                                ownedPalettes = (acc.safeOwnedPalettes() + pal.name).distinct()
                            ).updateActivePet { it.copy(paletteName = pal.name) }
                        }
                    }
                },
                onBuyOrEquipTheme = { theme ->
                    val owned = theme.id in account.safeOwned()
                    if (!owned && account.coins < theme.price) {
                        toastBubble = "Не хватает монет на обои!"
                    } else {
                        SoundEngine.playHappy()
                        updateAccount { acc ->
                            val cost = if (owned) 0 else theme.price
                            acc.copy(
                                coins = acc.coins - cost,
                                ownedItems = (acc.safeOwned() + theme.id).distinct(),
                                roomThemeId = theme.id
                            )
                        }
                    }
                },
                onBuyOrEquipDecor = { decor ->
                    val owned = decor.id in account.safeOwned()
                    if (!owned && account.coins < decor.price) {
                        toastBubble = "Не хватает монет на мебель!"
                    } else {
                        SoundEngine.playHappy()
                        updateAccount { acc ->
                            val cost = if (owned) 0 else decor.price
                            acc.copy(
                                coins = acc.coins - cost,
                                ownedItems = (acc.safeOwned() + decor.id).distinct(),
                                roomDecorId = decor.id
                            )
                        }
                    }
                }
            )

            currentRoom == RoomSceneType.ARCADE -> ArcadeRoomPanel(
                highCatch = account.highScoreCatch,
                highRunner = account.highScoreRunner,
                highSnake = account.highScoreSnake,
                onSelectGame = { activeGame = it },
                onBackHome = { currentRoom = RoomSceneType.BEDROOM }
            )

            currentRoom in listOf(
                RoomSceneType.CITY_SCHOOL,
                RoomSceneType.CITY_WORK,
                RoomSceneType.CITY_PARK,
                RoomSceneType.CITY_CINEMA,
                RoomSceneType.CITY_TRAVEL
            ) -> CityRoomPanel(
                account = account,
                state = state,
                currentRoom = currentRoom,
                activeTravelId = activeTravelId,
                onSwitchCityScene = { currentRoom = it },
                onBackHome = { currentRoom = RoomSceneType.BEDROOM },
                onOpenBag = { modal = ActiveModal.CITY_BAG },
                onAttendLesson = { lesson ->
                    if (state.energy < lesson.energyCost) {
                        toastBubble = "Мало энергии! Поспи немного!"
                    } else {
                        SoundEngine.playHatch()
                        actionAnim = PetAnimAction.PRAISING
                        val drop = if (Random.nextInt(100) < 60) lesson.dropPool.random() else null
                        val dropItem = drop?.let { CollectibleCatalog.getById(it) }
                        toastBubble = "Урок пройден! +${lesson.skillGain} ${lesson.skill.emoji}" +
                            (if (dropItem != null) " • Найдено: ${dropItem.emoji} ${dropItem.name}!" else "")
                        updateAccount { acc ->
                            val nextAcc = if (drop != null) acc.addCollectible(drop) else acc
                            nextAcc.updateActivePet { cur ->
                                cur.copy(
                                    energy = (cur.energy - lesson.energyCost).coerceAtLeast(5),
                                    iq = cur.iq + lesson.iqGain,
                                    skillIntellect = cur.skillIntellect + if (lesson.skill == SkillType.INTELLECT) lesson.skillGain else 0,
                                    skillAgility = cur.skillAgility + if (lesson.skill == SkillType.AGILITY) lesson.skillGain else 0,
                                    skillCharisma = cur.skillCharisma + if (lesson.skill == SkillType.CHARISMA) lesson.skillGain else 0,
                                    skillCreativity = cur.skillCreativity + if (lesson.skill == SkillType.CREATIVITY) lesson.skillGain else 0
                                )
                            }
                        }
                    }
                },
                onDoJob = { job ->
                    when {
                        state.skillIntellect < job.reqIntellect || state.skillAgility < job.reqAgility || state.skillCharisma < job.reqCharisma -> {
                            toastBubble = "Нужны навыки из Школы! (🧠${job.reqIntellect} ⚡${job.reqAgility} 🌟${job.reqCharisma})"
                        }
                        state.energy < job.energyCost -> {
                            toastBubble = "Питомец устал для смены!"
                        }
                        else -> {
                            SoundEngine.playHappy()
                            actionAnim = PetAnimAction.DANCING
                            val bonusDrop = if (Random.nextInt(100) < 55) CollectibleCatalog.getById(job.dropId) else null
                            toastBubble = "Зарплата +${job.payCoins} 🪙!" + (if (bonusDrop != null) " + ${bonusDrop.emoji} ${bonusDrop.name}" else "")
                            updateAccount { acc ->
                                val withCoins = acc.copy(coins = acc.coins + job.payCoins)
                                val withDrop = if (bonusDrop != null) withCoins.addCollectible(bonusDrop.id) else withCoins
                                withDrop.updateActivePet {
                                    it.copy(energy = (it.energy - job.energyCost).coerceAtLeast(5))
                                }
                            }
                        }
                    }
                },
                onParkWalk = {
                    if (state.energy < 8) toastBubble = "Сначала отдохни!"
                    else {
                        SoundEngine.playHappy()
                        actionAnim = PetAnimAction.PLAYING_TOY
                        val found = CollectibleCatalog.getById(listOf("clover", "sakura_branch", "bookmark").random())
                        toastBubble = "Чудесная прогулка! +18 ♥" + (if (found != null) " • Нашёл ${found.emoji} ${found.name}!" else "")
                        updateAccount { acc ->
                            val nextAcc = if (found != null) acc.addCollectible(found.id) else acc
                            nextAcc.updateActivePet {
                                it.copy(
                                    mood = (it.mood + 18).coerceAtMost(100),
                                    health = (it.health + 5).coerceAtMost(100),
                                    energy = (it.energy - 8).coerceAtLeast(5),
                                    skillAgility = it.skillAgility + 1
                                )
                            }
                        }
                    }
                },
                onCinemaWatch = {
                    if (account.coins < 20) toastBubble = "Билет стоит 20 🪙!"
                    else {
                        SoundEngine.playHatch()
                        actionAnim = PetAnimAction.SINGING
                        val dropId = if (Random.nextInt(100) < 65) "popcorn" else "glasses3d"
                        val dropItem = CollectibleCatalog.getById(dropId)
                        toastBubble = "Классный фильм! +25 ♥" + (if (dropItem != null) " • ${dropItem.emoji} ${dropItem.name}!" else "")
                        updateAccount { acc ->
                            acc.copy(coins = acc.coins - 20).addCollectible(dropId).updateActivePet {
                                it.copy(
                                    mood = (it.mood + 25).coerceAtMost(100),
                                    skillCreativity = it.skillCreativity + 2,
                                    skillCharisma = it.skillCharisma + 1
                                )
                            }
                        }
                    }
                },
                onTravelTrip = { trip ->
                    if (account.coins < trip.price) toastBubble = "На билет нужно ${trip.price} 🪙!"
                    else {
                        activeTravelId = trip.id
                        SoundEngine.playHatch()
                        actionAnim = PetAnimAction.DANCING
                        val dropItem = CollectibleCatalog.getById(trip.dropId)
                        toastBubble = "Прилетели: ${trip.title}! +${dropItem?.emoji ?: "🎁"}"
                        updateAccount { acc ->
                            acc.copy(
                                coins = acc.coins - trip.price,
                                postcards = (acc.safePostcards() + "${trip.emoji} ${trip.title}: ${trip.postcardText}").distinct()
                            ).addCollectible(trip.dropId).updateActivePet {
                                it.copy(
                                    mood = (it.mood + trip.moodGain).coerceAtMost(100),
                                    skillCreativity = it.skillCreativity + trip.creativityGain,
                                    affection = it.affection + 5
                                )
                            }
                        }
                    }
                }
            )
        }
    }

    // --- ЗАВЕРШЕНИЕ МИНИ-ИГР ---
    val finishGame: (MiniGameResult) -> Unit = { res ->
        SoundEngine.playHatch()
        SoundEngine.vibrate(context, 30L)
        activeGame = MiniGameType.NONE
        actionAnim = PetAnimAction.DANCING
        toastBubble = "Победа! +${res.coinsEarned} 🪙 и +${res.moodGain} ♥"
        updateAccount { acc ->
            acc.copy(
                coins = acc.coins + res.coinsEarned,
                highScoreCatch = if (res.gameType == MiniGameType.CATCH) maxOf(acc.highScoreCatch, res.score) else acc.highScoreCatch,
                highScoreRunner = if (res.gameType == MiniGameType.RUNNER) maxOf(acc.highScoreRunner, res.score) else acc.highScoreRunner,
                highScoreSnake = if (res.gameType == MiniGameType.SNAKE) maxOf(acc.highScoreSnake, res.score) else acc.highScoreSnake
            ).updateActivePet { cur ->
                cur.copy(
                    mood = (cur.mood + res.moodGain).coerceAtMost(100),
                    iq = cur.iq + res.iqGain,
                    weight = (cur.weight + res.weightDelta).coerceIn(3, 99),
                    energy = (cur.energy - 3).coerceAtLeast(5),
                    skillAgility = cur.skillAgility + if (res.gameType == MiniGameType.RUNNER || res.gameType == MiniGameType.CATCH) 1 else 0,
                    skillIntellect = cur.skillIntellect + if (res.iqGain >= 3) 1 else 0
                )
            }
        }
    }

    when (activeGame) {
        MiniGameType.CATCH -> CatchMiniGameDialog(pet, palette, state.equippedHat, state.equippedFace, finishGame) { activeGame = MiniGameType.NONE }
        MiniGameType.RUNNER -> RunnerMiniGameDialog(pet, palette, state.equippedHat, state.equippedFace, finishGame) { activeGame = MiniGameType.NONE }
        MiniGameType.SNAKE -> SnakeMiniGameDialog(finishGame) { activeGame = MiniGameType.NONE }
        MiniGameType.WHACK -> WhackSlimeMiniGameDialog(finishGame) { activeGame = MiniGameType.NONE }
        MiniGameType.SIMON -> SimonMiniGameDialog(finishGame) { activeGame = MiniGameType.NONE }
        MiniGameType.QUIZ -> QuizMiniGameDialog(finishGame) { activeGame = MiniGameType.NONE }
        MiniGameType.DIRECTION -> DirectionMiniGameDialog(pet, palette, state.equippedHat, state.equippedFace, finishGame) { activeGame = MiniGameType.NONE }
        MiniGameType.MEMORY -> MemoryMiniGameDialog(finishGame) { activeGame = MiniGameType.NONE }
        MiniGameType.NONE -> {}
    }

    // --- МОДАЛЬНЫЕ ОКНА ---
    when (modal) {
        ActiveModal.STARTER -> StarterPickerDialog(
            onPick = { chosen ->
                val rndPal = PetPalette.random()
                SoundEngine.playHatch()
                toastBubble = "Выбрано яйцо: ${chosen.name}! Тапай по нему!"
                updateAccount { acc ->
                    if (acc.myPets.isEmpty()) acc.addPetAndActivate(chosen, rndPal, asEgg = true)
                    else acc.addPetAndActivate(chosen, rndPal, asEgg = true, nextGen = acc.activePet().generation + 1)
                }
                modal = ActiveModal.NONE
            }
        )

        ActiveModal.MY_PETS -> MyPetsHotelDialog(
            account = account,
            frame = animFrame,
            onDismiss = { modal = ActiveModal.NONE },
            onSwitchPet = { uid ->
                SoundEngine.playHappy()
                updateAccount { it.switchToPet(uid) }
                currentRoom = RoomSceneType.BEDROOM
                toastBubble = "Привет! Рад снова проснуться! ♥"
                modal = ActiveModal.NONE
            },
            onAddNewEgg = { modal = ActiveModal.STARTER },
            onOpenGacha = { modal = ActiveModal.GACHA }
        )

        ActiveModal.GACHA -> GachaCapsuleDialog(
            coins = account.coins,
            unlockedIds = account.safeUnlockedPets(),
            onDismiss = { modal = ActiveModal.NONE },
            onRoll = { capsule ->
                if (account.coins < capsule.price) {
                    toastBubble = "Не хватает монет! Поиграй в Игры!"
                    null
                } else {
                    val (wonPet, wonPal) = GachaCatalog.roll(capsule)
                    val isNew = wonPet.id !in account.safeUnlockedPets()
                    val refund = if (isNew) 0 else capsule.price / 3
                    updateAccount { cur ->
                        cur.copy(
                            coins = cur.coins - capsule.price + refund,
                            unlockedPetIds = (cur.safeUnlockedPets() + wonPet.id).distinct(),
                            ownedPalettes = (cur.safeOwnedPalettes() + wonPal.name).distinct()
                        )
                    }
                    Triple(wonPet, wonPal, isNew)
                }
            },
            onHatchInNewSlot = { wonPet, wonPal ->
                SoundEngine.playHatch()
                toastBubble = "Новый питомец в Отеле: ${wonPet.name}!"
                updateAccount { it.addPetAndActivate(wonPet, wonPal, asEgg = true, nextGen = it.activePet().generation + 1) }
                currentRoom = RoomSceneType.BEDROOM
                modal = ActiveModal.NONE
            }
        )

        ActiveModal.CATALOG -> PetCatalogDialog(
            currentPetId = state.petId,
            unlockedIds = account.safeUnlockedPets(),
            onDismiss = { modal = ActiveModal.NONE },
            onOpenGacha = { modal = ActiveModal.GACHA },
            onAdoptPet = { selected ->
                val rndPal = PetPalette.random()
                SoundEngine.playHatch()
                toastBubble = "Добавлен в Отель: ${selected.name}!"
                updateAccount { it.addPetAndActivate(selected, rndPal, asEgg = true) }
                currentRoom = RoomSceneType.BEDROOM
                modal = ActiveModal.NONE
            }
        )

        ActiveModal.PROFILE -> ProfileDialog(
            state = state,
            pet = pet,
            palette = palette,
            onDismiss = { modal = ActiveModal.NONE },
            onRename = { newName -> updateActivePet { it.copy(customName = newName.ifBlank { pet.name }) } },
            onGrowFast = {
                SoundEngine.playHappy()
                val nextStage = when (state.stage()) {
                    PetStage.EGG -> PetStage.BABY
                    PetStage.BABY -> PetStage.CHILD
                    PetStage.CHILD -> PetStage.TEEN
                    PetStage.TEEN -> PetStage.ADULT
                    PetStage.ADULT -> PetStage.ADULT
                }
                toastBubble = "Эволюция: ${nextStage.title}! ★"
                updateActivePet {
                    it.copy(
                        stageName = nextStage.name,
                        ageMinutes = maxOf(it.ageMinutes, nextStage.minAgeMinutes),
                        iq = it.iq + 5
                    )
                }
                modal = ActiveModal.NONE
            },
            onOpenMyPets = { modal = ActiveModal.MY_PETS }
        )

        ActiveModal.CITY_BAG -> CityBagAndCraftDialog(
            account = account,
            onDismiss = { modal = ActiveModal.NONE },
            onGiftToPet = { item ->
                updateAccount { acc ->
                    val updated = acc.consumeCollectibles(mapOf(item.id to 1)) ?: acc
                    updated.updateActivePet {
                        it.copy(
                            mood = (it.mood + item.moodBoost).coerceAtMost(100),
                            affection = it.affection + item.affectionBoost
                        )
                    }
                }
                SoundEngine.playHappy()
                actionAnim = PetAnimAction.PETTING
                toastBubble = "Подарок ${item.emoji} ${item.name} очень понравился! ♥"
                modal = ActiveModal.NONE
            },
            onCraft = { recipe ->
                val consumed = account.consumeCollectibles(recipe.ingredients)
                if (consumed == null) {
                    toastBubble = "Не хватает предметов с локаций!"
                } else {
                    SoundEngine.playHatch()
                    updateAccount {
                        val after = it.consumeCollectibles(recipe.ingredients) ?: it
                        after.copy(
                            ownedItems = (after.safeOwned() + recipe.rewardWardrobeId).distinct(),
                            craftedCount = after.craftedCount + 1
                        )
                    }
                    toastBubble = "Скрафтили: ${recipe.emoji} ${recipe.title}! Ищи в Бутике!"
                }
            }
        )

        ActiveModal.DAILY_BONUS -> DailyBonusDialog(
            account = account,
            onDismiss = { modal = ActiveModal.NONE },
            onClaim = { reward ->
                val today = account.currentEpochDay()
                SoundEngine.playHatch()
                SoundEngine.vibrate(context, 40L)
                updateAccount { acc ->
                    var next = acc.copy(
                        coins = acc.coins + reward.coins,
                        lastDailyClaimDay = today,
                        dailyStreak = reward.day
                    )
                    if (reward.collectibleId != null) {
                        next = next.addCollectible(reward.collectibleId)
                    }
                    if (reward.freeLegendRoll) {
                        val (wonPet, wonPal) = GachaCatalog.roll(GachaCatalog.capsules.last())
                        next = next.copy(
                            unlockedPetIds = (next.safeUnlockedPets() + wonPet.id).distinct(),
                            ownedPalettes = (next.safeOwnedPalettes() + wonPal.name).distinct()
                        )
                    }
                    next
                }
                toastBubble = if (reward.freeLegendRoll) "🎁 День 7! +${reward.coins} 🪙 + Легендарный вид в Альманахе!"
                else "🎁 Ежедневный бонус: +${reward.coins} 🪙!"
                modal = ActiveModal.NONE
            }
        )

        ActiveModal.ALBUM_ACH -> AlbumAndAchievementsDialog(
            account = account,
            onDismiss = { modal = ActiveModal.NONE }
        )

        ActiveModal.SETTINGS -> SettingsDialog(
            account = account,
            onDismiss = { modal = ActiveModal.NONE },
            onVolumeChange = { vol ->
                SoundEngine.volume = vol
                updateAccount { it.copy(soundVolume = vol) }
            },
            onMuteToggle = { isMuted ->
                SoundEngine.muted = isMuted
                updateAccount { it.copy(soundMuted = isMuted) }
            },
            onHapticsToggle = { enabled ->
                SoundEngine.hapticsEnabled = enabled
                updateAccount { it.copy(hapticsEnabled = enabled) }
            },
            onNotificationsToggle = { enabled ->
                updateAccount { it.copy(notificationsEnabled = enabled) }
            },
            onQuietHoursToggle = { enabled ->
                updateAccount { it.copy(quietHoursEnabled = enabled) }
            },
            onWeatherModeChange = { mode ->
                updateAccount { it.copy(weatherMode = mode) }
            },
            onImportAccount = { restored ->
                SoundEngine.volume = restored.soundVolume
                SoundEngine.muted = restored.soundMuted
                SoundEngine.hapticsEnabled = restored.hapticsEnabled
                account = restored
                PetStorage.save(context, restored)
                toastBubble = "Сохранение успешно восстановлено! ★"
                modal = ActiveModal.NONE
            }
        )

        ActiveModal.NONE -> {}
    }
}

// =====================================================================
// ПАНЕЛЬ ГОРОДА (ШКОЛА, РАБОТА, ПАРК, КИНО, ПУТЕШЕСТВИЯ)
// =====================================================================

@Composable
private fun CityRoomPanel(
    account: AccountSaveState,
    state: PetSaveState,
    currentRoom: RoomSceneType,
    activeTravelId: String,
    onSwitchCityScene: (RoomSceneType) -> Unit,
    onBackHome: () -> Unit,
    onOpenBag: () -> Unit,
    onAttendLesson: (SchoolLesson) -> Unit,
    onDoJob: (CityJob) -> Unit,
    onParkWalk: () -> Unit,
    onCinemaWatch: () -> Unit,
    onTravelTrip: (TravelTrip) -> Unit
) {
    val tabs = remember {
        listOf(
            CityHubTab("Школа", "🏫", RoomSceneType.CITY_SCHOOL),
            CityHubTab("Работа", "💼", RoomSceneType.CITY_WORK),
            CityHubTab("Парк", "🌳", RoomSceneType.CITY_PARK),
            CityHubTab("Кино", "🎬", RoomSceneType.CITY_CINEMA),
            CityHubTab("Туры", "✈️", RoomSceneType.CITY_TRAVEL)
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF21173E),
        modifier = Modifier.fillMaxWidth().height(235.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { t ->
                    val active = currentRoom == t.scene
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = if (active) Color(0xFF00B4D8) else Color(0xFF2E2252),
                        modifier = Modifier.weight(1f).clickable { SoundEngine.playClick(); onSwitchCityScene(t.scene) }
                    ) {
                        Text("${t.emoji}\n${t.title}", modifier = Modifier.padding(vertical = 4.dp), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp, textAlign = TextAlign.Center, lineHeight = 11.sp)
                    }
                }
                Surface(shape = RoundedCornerShape(9.dp), color = Color(0xFFE84393), modifier = Modifier.clickable { onOpenBag() }) {
                    Text("🎒", modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp), fontSize = 13.sp)
                }
                Surface(shape = RoundedCornerShape(9.dp), color = Color(0xFF6C5CE7), modifier = Modifier.clickable { onBackHome() }) {
                    Text("🏠", modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp), fontSize = 13.sp)
                }
            }

            // Полоска текущих навыков
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("🧠${state.skillIntellect}  ⚡${state.skillAgility}  🌟${state.skillCharisma}  🎨${state.skillCreativity}", color = Color(0xFF9BF6FF), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text("Штучек: ${account.safeCollectibles().values.sum()}", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }

            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (currentRoom) {
                    RoomSceneType.CITY_SCHOOL -> {
                        CityCatalog.lessons.forEach { lesson ->
                            CityActionRow(
                                emoji = lesson.emoji,
                                title = lesson.title,
                                subtitle = "+${lesson.skillGain} ${lesson.skill.title}, +${lesson.iqGain} IQ • -${lesson.energyCost}⚡",
                                actionText = "Учиться",
                                btnColor = Color(lesson.skill.colorHex)
                            ) { onAttendLesson(lesson) }
                        }
                    }
                    RoomSceneType.CITY_WORK -> {
                        CityCatalog.jobs.forEach { job ->
                            val unlocked = state.skillIntellect >= job.reqIntellect && state.skillAgility >= job.reqAgility && state.skillCharisma >= job.reqCharisma
                            CityActionRow(
                                emoji = job.emoji,
                                title = job.title,
                                subtitle = if (unlocked) "+${job.payCoins} 🪙 • -${job.energyCost}⚡ • шанс на сувенир" else "Нужно: 🧠${job.reqIntellect} ⚡${job.reqAgility} 🌟${job.reqCharisma}",
                                actionText = if (unlocked) "Работать" else "Закрыто",
                                btnColor = if (unlocked) Color(0xFF00B894) else Color(0xFF534085)
                            ) { onDoJob(job) }
                        }
                    }
                    RoomSceneType.CITY_PARK -> {
                        CityActionRow("🌳", "Прогулка по парку", "+18 ♥ счастья, +5 ❤, шанс найти клевер/сакуру (-8⚡)", "Гулять", Color(0xFF2ECC71)) { onParkWalk() }
                        CityActionRow("🎒", "Рюкзак находок и Крафт", "Подари находки питомцу или скрафти редкие вещи!", "Открыть", Color(0xFFE84393)) { onOpenBag() }
                    }
                    RoomSceneType.CITY_CINEMA -> {
                        CityActionRow("🎬", "Посмотреть кино", "+25 ♥ счастья, +2 🎨 Творчество + попкорн/билет (20 🪙)", "Билет 20🪙", Color(0xFFE84393)) { onCinemaWatch() }
                    }
                    RoomSceneType.CITY_TRAVEL -> {
                        CityCatalog.trips.forEach { trip ->
                            CityActionRow(
                                emoji = trip.emoji,
                                title = if (activeTravelId == trip.id) "${trip.title} ★" else trip.title,
                                subtitle = "+${trip.moodGain} ♥ +${trip.creativityGain} 🎨 + открытка и сувенир",
                                actionText = "${trip.price} 🪙",
                                btnColor = Color(0xFF0984E3)
                            ) { onTravelTrip(trip) }
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun CityActionRow(
    emoji: String,
    title: String,
    subtitle: String,
    actionText: String,
    btnColor: Color,
    onClick: () -> Unit
) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF2E2252), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 21.sp)
            Spacer(modifier = Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Color(0xFFCBB8FF), fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Surface(shape = RoundedCornerShape(8.dp), color = btnColor) {
                Text(actionText, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = Color(0xFF130D26), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
            }
        }
    }
}

// =====================================================================
// РЮКЗАК НАХОДОК, КРАФТ И ОТКРЫТКИ
// =====================================================================

@Composable
private fun CityBagAndCraftDialog(
    account: AccountSaveState,
    onDismiss: () -> Unit,
    onGiftToPet: (CollectibleItem) -> Unit,
    onCraft: (CraftRecipe) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) } // 0 = Находки, 1 = Крафт, 2 = Открытки
    val bag = account.safeCollectibles()
    val owned = account.safeOwned()
    val cards = account.safePostcards()

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF21173E), modifier = Modifier.fillMaxWidth().fillMaxHeight(0.84f)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🎒 Рюкзак и Мастерская", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ShopTabChip("🎁 Штучки (${bag.values.sum()})", tab == 0, Modifier.weight(1f)) { tab = 0 }
                    ShopTabChip("🛠️ Крафт", tab == 1, Modifier.weight(1f)) { tab = 1 }
                    ShopTabChip("🖼️ Открытки (${cards.size})", tab == 2, Modifier.weight(1f)) { tab = 2 }
                }

                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    when (tab) {
                        0 -> {
                            if (bag.isEmpty()) {
                                Text("Пока пусто! Сходи в Школу, на Работу, в Парк или в Путешествие.", color = Color(0xFFCBB8FF), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                            CollectibleCatalog.items.forEach { item ->
                                val qty = bag[item.id] ?: 0
                                if (qty > 0) {
                                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF2E2252), modifier = Modifier.fillMaxWidth()) {
                                        Row(modifier = Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(item.emoji, fontSize = 22.sp)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("${item.name} ×$qty", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                Text("${item.source} • +${item.moodBoost} ♥", color = Color(item.rarity.colorHex), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                                            }
                                            Button(onClick = { onGiftToPet(item) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE84393))) {
                                                Text("Подарить", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        1 -> {
                            CraftCatalog.recipes.forEach { rec ->
                                val already = rec.rewardWardrobeId in owned
                                val canCraft = !already && rec.ingredients.all { (k, v) -> (bag[k] ?: 0) >= v }
                                val ingText = rec.ingredients.entries.joinToString(" + ") { (k, v) ->
                                    val c = CollectibleCatalog.getById(k)
                                    "${c?.emoji ?: k} ${bag[k] ?: 0}/$v"
                                }
                                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF2E2252), modifier = Modifier.fillMaxWidth()) {
                                    Row(modifier = Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(rec.emoji, fontSize = 22.sp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(rec.title, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            Text("Нужно: $ingText", color = Color(0xFFCBB8FF), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (already) Color(0xFF00B894) else if (canCraft) Color(0xFFFFB703) else Color(0xFF534085),
                                            modifier = Modifier.clickable(enabled = canCraft) { onCraft(rec) }
                                        ) {
                                            Text(
                                                text = if (already) "Собрано ✓" else if (canCraft) "Создать!" else "Не хватает",
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                                color = if (canCraft) Color(0xFF130D26) else Color.White,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        2 -> {
                            if (cards.isEmpty()) {
                                Text("Открыток пока нет — слетай в путешествие! ✈️", color = Color(0xFFCBB8FF), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                            cards.forEach { c ->
                                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF2E2252), modifier = Modifier.fillMaxWidth()) {
                                    Text(c, modifier = Modifier.padding(10.dp), color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Закрыть", color = Color(0xFFFF85B3)) }
            }
        }
    }
}

// =====================================================================
// МЕНЮ НАСТРОЕК (ГРОМКОСТЬ, ВИБРО, БЭКАП, ПРОВЕРКА ОБНОВЛЕНИЙ GITHUB)
// =====================================================================

@Composable
private fun SettingsDialog(
    account: AccountSaveState,
    onDismiss: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onMuteToggle: (Boolean) -> Unit,
    onHapticsToggle: (Boolean) -> Unit,
    onNotificationsToggle: (Boolean) -> Unit,
    onQuietHoursToggle: (Boolean) -> Unit,
    onWeatherModeChange: (String) -> Unit,
    onImportAccount: (AccountSaveState) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onNotificationsToggle(granted)
    }
    val currentVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: Throwable) {
            "1.0"
        }
    }

    var vol by remember { mutableFloatStateOf(account.soundVolume) }
    var muted by remember { mutableStateOf(account.soundMuted) }
    var haptics by remember { mutableStateOf(account.hapticsEnabled) }
    var notifs by remember { mutableStateOf(account.notificationsEnabled) }
    var quiet by remember { mutableStateOf(account.quietHoursEnabled) }
    var weatherMode by remember { mutableStateOf(account.weatherMode) }
    var updateStatus by remember { mutableStateOf("Текущая версия: v$currentVersion") }
    var downloadUrl by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var backupInput by remember { mutableStateOf("") }
    var backupMsg by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF21173E), modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("⚙️ Настройки TamaVerse", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)

                // Громкость и вибрация
                Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF2E2252), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("🔊 Громкость: ${(vol * 100).roundToInt()}%", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Switch(checked = !muted, onCheckedChange = { on -> muted = !on; onMuteToggle(muted) })
                        }
                        Slider(
                            value = vol,
                            onValueChange = { vol = it; SoundEngine.volume = it },
                            onValueChangeFinished = { onVolumeChange(vol); SoundEngine.playClick() },
                            valueRange = 0f..1f
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("📳 Виброотклик при нажатиях", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Switch(checked = haptics, onCheckedChange = { haptics = it; onHapticsToggle(it) })
                        }
                    }
                }

                // Уведомления и Погода
                Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF2E2252), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("🔔 Напоминания об уходе", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Switch(checked = notifs, onCheckedChange = { want ->
                                notifs = want
                                if (want && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    onNotificationsToggle(want)
                                }
                            })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("🌙 Тихие часы (23:00–08:00)", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Switch(checked = quiet, onCheckedChange = { quiet = it; onQuietHoursToggle(it) })
                        }
                        Text("🌦️ Погода за окном:", color = Color(0xFF9BF6FF), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            FilterChipItem("🔄 Авто", weatherMode == "AUTO") { weatherMode = "AUTO"; onWeatherModeChange("AUTO") }
                            WeatherType.entries.forEach { w ->
                                FilterChipItem("${w.emoji} ${w.title}", weatherMode == w.name) { weatherMode = w.name; onWeatherModeChange(w.name) }
                            }
                        }
                    }
                }

                // Проверка обновлений GitHub Releases (cakecock/TamaVerse)
                Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF2E2252), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🚀 Обновления (GitHub Releases)", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(updateStatus, color = Color(0xFFCBB8FF), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Button(
                            enabled = !checking,
                            onClick = {
                                checking = true
                                updateStatus = "Проверяю последний релиз..."
                                downloadUrl = null
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        try {
                                            val conn = (URL("https://api.github.com/repos/cakecock/TamaVerse/releases/latest").openConnection() as HttpURLConnection).apply {
                                                requestMethod = "GET"
                                                setRequestProperty("Accept", "application/vnd.github+json")
                                                connectTimeout = 8000
                                                readTimeout = 8000
                                            }
                                            if (conn.responseCode == 200) {
                                                val body = conn.inputStream.bufferedReader().readText()
                                                val json = JSONObject(body)
                                                val tag = json.optString("tag_name", "")
                                                val assets = json.optJSONArray("assets")
                                                var apkLink: String? = null
                                                if (assets != null) {
                                                    for (i in 0 until assets.length()) {
                                                        val a = assets.getJSONObject(i)
                                                        val url = a.optString("browser_download_url", "")
                                                        if (url.endsWith(".apk")) {
                                                            apkLink = url
                                                            break
                                                        }
                                                    }
                                                }
                                                Triple(tag, apkLink ?: json.optString("html_url", ""), null)
                                            } else {
                                                Triple("", "", "Релизы пока не найдены (код ${conn.responseCode})")
                                            }
                                        } catch (e: Throwable) {
                                            Triple("", "", "Ошибка сети: ${e.message ?: "нет связи"}")
                                        }
                                    }
                                    checking = false
                                    if (res.third != null) {
                                        updateStatus = res.third!!
                                    } else {
                                        val cleanTag = res.first.removePrefix("v")
                                        if (cleanTag == currentVersion) {
                                            updateStatus = "✅ У тебя самая свежая версия ($cleanTag)!"
                                        } else {
                                            updateStatus = "🎉 Доступна новая версия: ${res.first} (у тебя v$currentVersion)"
                                            downloadUrl = res.second
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0984E3))
                        ) {
                            Text(if (checking) "Идёт проверка..." else "🔄 Проверить обновления", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        if (!downloadUrl.isNullOrBlank()) {
                            Button(
                                onClick = {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
                                    } catch (_: Throwable) {
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B894))
                            ) {
                                Text("⬇️ Скачать новый APK", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                }

                // Резервная копия
                Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF2E2252), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("💾 Резервная копия сохранения", color = Color(0xFF7BE495), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Button(
                            onClick = {
                                val code = PetStorage.exportBackupCode(account)
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("TamaVerseBackup", code))
                                backupMsg = "Код сохранения скопирован в буфер обмена!"
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C5CE7))
                        ) {
                            Text("📋 Скопировать код сохранения", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        OutlinedTextField(
                            value = backupInput,
                            onValueChange = { backupInput = it },
                            label = { Text("Вставить код восстановления") },
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                val parsed = PetStorage.importBackupCode(backupInput)
                                if (parsed == null) backupMsg = "❌ Неверный код сохранения"
                                else onImportAccount(parsed)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE84393))
                        ) {
                            Text("♻️ Восстановить из кода", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        if (!backupMsg.isNullOrBlank()) {
                            Text(backupMsg!!, color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                }

                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Закрыть", color = Color(0xFFFF85B3)) }
            }
        }
    }
}

// =====================================================================
// ПАНЕЛИ БАЗОВЫХ КОМНАТ И ОСТАЛЬНЫЕ ДИАЛОГИ
// =====================================================================

@Composable
private fun RowScope.MiniInteractChip(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        color = Color(0xFF2B204A),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 3.dp),
            color = Color(0xFFF3E8FF),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DeceasedControlBar(
    petName: String,
    coins: Int,
    onRevive: () -> Unit,
    onRetire: () -> Unit
) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF281C48), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("👼 $petName стал ангелочком от сильного голода или болезни...", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRevive, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (coins >= PetSaveState.REVIVE_COST) Color(0xFF00B894) else Color(0xFF534875))) {
                    Text("✨ Возродить (${PetSaveState.REVIVE_COST} 🪙)", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Button(onClick = onRetire, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C5CE7))) {
                    Text("🕊️ В Зал Памяти", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun KitchenRoomPanel(
    coins: Int,
    favoriteFoodId: String,
    onBackHome: () -> Unit,
    onFeed: (FoodItem) -> Unit
) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF221742), modifier = Modifier.fillMaxWidth().height(175.dp)) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("🍽️ Меню кухни (★ — любимое!)", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                Text("🪙 $coins", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Row(modifier = Modifier.fillMaxWidth().weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FoodCatalog.items.forEach { food ->
                    val isFav = food.id == favoriteFoodId
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isFav) Color(0xFF3E2469) else Color(0xFF2E2252),
                        modifier = Modifier
                            .width(105.dp)
                            .fillMaxHeight()
                            .border(1.5.dp, if (isFav) Color(0xFFFFD166) else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable { onFeed(food) }
                    ) {
                        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                            Text(if (isFav) "${food.emoji} ★" else food.emoji, fontSize = 24.sp)
                            Text(food.name, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("+${food.hunger} сыт. +${food.mood}♥", color = Color(0xFF9DF0B8), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            Text(if (food.price == 0) "Бесплатно" else "${food.price} 🪙", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        }
                    }
                }
            }
            Button(onClick = onBackHome, modifier = Modifier.fillMaxWidth().height(34.dp), contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C5CE7))) {
                Text("🏠 Вернуться в спальню", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun BathroomRoomPanel(
    poopCount: Int,
    onBath: () -> Unit,
    onCleanRoom: () -> Unit,
    onBackHome: () -> Unit
) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF1F244D), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🛁 Ванная комната", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModernArcadeButton("Искупать (100%)", Icons.Rounded.Bathtub, Color(0xFF00B4D8), Color(0xFF0077B6), Modifier.weight(1f), onBath)
                ModernArcadeButton("Убрать ($poopCount)", Icons.Rounded.CleaningServices, Color(0xFF1DD1A1), Color(0xFF008F6B), Modifier.weight(1f), onCleanRoom)
                ModernArcadeButton("В спальню", Icons.Rounded.Home, Color(0xFF7B61FF), Color(0xFF4328B8), Modifier.weight(1f), onBackHome)
            }
        }
    }
}

@Composable
private fun BoutiqueRoomPanel(
    account: AccountSaveState,
    state: PetSaveState,
    pet: PetDefinition,
    onBackHome: () -> Unit,
    onBuyOrEquipWardrobe: (GeneratedWardrobeItem) -> Unit,
    onBuyOrEquipPalette: (PetPalette) -> Unit,
    onBuyOrEquipTheme: (RoomTheme) -> Unit,
    onBuyOrEquipDecor: (RoomDecor) -> Unit
) {
    var topTab by remember { mutableIntStateOf(0) }
    var selectedSlot by remember { mutableStateOf(WardrobeSlot.HEAD) }
    val owned = account.safeOwned()
    val ownedPals = account.safeOwnedPalettes()
    val eq = state.equipped()

    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF21173E), modifier = Modifier.fillMaxWidth().height(235.dp)) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShopTabChip("👗 Вещи (152)", topTab == 0, Modifier.weight(1f)) { topTab = 0 }
                ShopTabChip("🎨 Окрас", topTab == 1, Modifier.weight(1f)) { topTab = 1 }
                ShopTabChip("🏠 Ремонт", topTab == 2, Modifier.weight(1f)) { topTab = 2 }
                Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF6C5CE7), modifier = Modifier.clickable { onBackHome() }) {
                    Text("🏠", modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 13.sp)
                }
            }

            if (topTab == 0) {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    WardrobeSlot.entries.forEach { slot ->
                        val active = slot == selectedSlot
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = if (active) Color(0xFFE84393) else Color(0xFF2E2252),
                            modifier = Modifier.clickable { SoundEngine.playClick(); selectedSlot = slot }
                        ) {
                            Text("${slot.emoji} ${slot.title}", modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        }
                    }
                }
            }

            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (topTab) {
                    0 -> {
                        val items = WardrobeGenerator.allItems.filter { it.slot == selectedSlot }
                        items.forEach { item ->
                            val isOwned = item.id in owned
                            val isEquipped = when (item.slot) {
                                WardrobeSlot.HEAD -> eq.head == item.id
                                WardrobeSlot.FACE -> eq.face == item.id
                                WardrobeSlot.TORSO -> eq.torso == item.id
                                WardrobeSlot.SHOES -> eq.shoes == item.id
                                WardrobeSlot.BACK -> eq.back == item.id
                                WardrobeSlot.HAND -> eq.hand == item.id
                                WardrobeSlot.AURA -> eq.aura == item.id
                            }
                            ShopItemRow(item.emoji, item.name, "${item.slot.title} • ${item.rarity.title}", item.price, isOwned, isEquipped, Color(item.rarity.colorHex)) {
                                onBuyOrEquipWardrobe(item)
                            }
                        }
                    }
                    1 -> {
                        val curPal = state.safePalette(pet)
                        PetPalette.entries.forEach { pal ->
                            ShopItemRow(pal.emoji, pal.title, "Цвет для ${pet.name}", pal.price, pal.name in ownedPals, curPal == pal, Color(pal.primary)) {
                                onBuyOrEquipPalette(pal)
                            }
                        }
                    }
                    2 -> {
                        Text("Обои и пол", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        RoomThemeCatalog.themes.forEach { theme ->
                            ShopItemRow(theme.emoji, theme.name, "Стиль спальни", theme.price, theme.id in owned, account.roomThemeId == theme.id, Color(theme.rugOuter)) {
                                onBuyOrEquipTheme(theme)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Мебель и декор", color = Color(0xFF7BE495), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        RoomDecorCatalog.items.forEach { decor ->
                            ShopItemRow(decor.emoji, decor.name, decor.desc, decor.price, decor.id in owned, account.roomDecorId == decor.id, Color(0xFF56CCF2)) {
                                onBuyOrEquipDecor(decor)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArcadeRoomPanel(
    highCatch: Int,
    highRunner: Int,
    highSnake: Int,
    onSelectGame: (MiniGameType) -> Unit,
    onBackHome: () -> Unit
) {
    val games = remember(highCatch, highRunner, highSnake) {
        listOf(
            Triple("🍓 Лови вкусняшки", "Рекорд: $highCatch", MiniGameType.CATCH),
            Triple("🏃 Пиксель-Раннер", "Рекорд: $highRunner", MiniGameType.RUNNER),
            Triple("🐍 Пиксель-Змейка", "Рекорд: $highSnake", MiniGameType.SNAKE),
            Triple("🔨 Поймай Слаймов", "На реакцию", MiniGameType.WHACK),
            Triple("🎵 Саймон", "Мелодия + IQ", MiniGameType.SIMON),
            Triple("🧮 Викторина", "+8 IQ!", MiniGameType.QUIZ),
            Triple("🧭 Направление", "Классика", MiniGameType.DIRECTION),
            Triple("🃏 Пиксель-Пары", "Мемори + IQ", MiniGameType.MEMORY)
        )
    }
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF21173E), modifier = Modifier.fillMaxWidth().height(195.dp)) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("🕹️ Игровой зал (8 мини-игр)", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                Surface(shape = RoundedCornerShape(9.dp), color = Color(0xFF6C5CE7), modifier = Modifier.clickable { onBackHome() }) {
                    Text("🏠 Домой", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(games) { (title, sub, type) ->
                    Surface(shape = RoundedCornerShape(11.dp), color = Color(0xFF31225E), modifier = Modifier.fillMaxWidth().clickable { SoundEngine.playClick(); onSelectGame(type) }) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(title, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(sub, color = Color(0xFFB8A9E8), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MyPetsHotelDialog(
    account: AccountSaveState,
    frame: Int,
    onDismiss: () -> Unit,
    onSwitchPet: (String) -> Unit,
    onAddNewEgg: () -> Unit,
    onOpenGacha: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF21173E), modifier = Modifier.fillMaxWidth().fillMaxHeight(0.84f)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🏨 Мои питомцы (Отель и Спячка)", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                Text("Неактивные питомцы спят в отеле — их потребности полностью заморожены!", color = Color(0xFFB8E994), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShopTabChip("🐾 В отеле (${account.myPets.size})", tab == 0, Modifier.weight(1f)) { tab = 0 }
                    ShopTabChip("🕊️ Память (${account.memorialPets.size})", tab == 1, Modifier.weight(1f)) { tab = 1 }
                }

                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tab == 0) {
                        account.myPets.forEach { pState ->
                            val def = PetCatalog.getById(pState.petId)
                            val pal = pState.safePalette(def)
                            val isActive = pState.uid == account.activePetUid
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isActive) Color(0xFF39296E) else Color(0xFF2B204A),
                                modifier = Modifier.fillMaxWidth().border(1.5.dp, if (isActive) Color(0xFF7BE495) else Color.Transparent, RoundedCornerShape(14.dp)).clickable { if (!isActive) onSwitchPet(pState.uid) }
                            ) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PixelPetAvatar(def, pState.stage(), pal, frame = frame, isSleeping = !isActive || pState.isSleeping, isDeceased = pState.isDeceased, equipped = pState.equipped(), modifier = Modifier.size(56.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${pState.customName} (${def.name})", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text("${pState.stage().title} • Пок.${pState.generation} • IQ ${pState.iq}", color = Color(0xFFCBB8FF), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                        Text(
                                            if (pState.isDeceased) "👼 Ждёт возрождения (${PetSaveState.REVIVE_COST} 🪙)" else "🍖${pState.hunger}% ♥${pState.mood}% ⚡${pState.energy}%",
                                            color = Color(0xFFFFD166),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp
                                        )
                                    }
                                    Surface(shape = RoundedCornerShape(8.dp), color = if (isActive) Color(0xFF00B894) else Color(0xFF534085)) {
                                        Text(if (isActive) "Активен" else "💤 Будить", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    } else {
                        if (account.memorialPets.isEmpty()) Text("Зал памяти пуст — пусть все питомцы живут долго! ♥", color = Color(0xFFCBB8FF), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        account.memorialPets.forEach { mp ->
                            val def = PetCatalog.getById(mp.petId)
                            Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF281F44), modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PixelPetAvatar(def, mp.stage(), mp.safePalette(def), isDeceased = true, modifier = Modifier.size(48.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text("🕊️ ${mp.customName} (${def.name})", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text("Возраст: ${mp.ageMinutes} мин • Поколение ${mp.generation}", color = Color(0xFFCBB8FF), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAddNewEgg, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE84393))) { Text("🥚 + Питомец", fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                    Button(onClick = onOpenGacha, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9F43))) { Text("🎰 Капсула", fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Закрыть", color = Color(0xFFFF85B3)) }
            }
        }
    }
}

@Composable
private fun ModernArcadeButton(
    label: String,
    icon: ImageVector,
    topColor: Color,
    bottomColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(bottomColor)
            .padding(bottom = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(topColor, bottomColor)))
            .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(20.dp))
            Text(label, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun BadgeChip(text: String, bg: Color, textDark: Boolean = false) {
    Surface(shape = RoundedCornerShape(8.dp), color = bg) {
        Text(text, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = if (textDark) Color(0xFF1A1233) else Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun StatBar(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFFD7C9FF), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            Text("$value", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Box(modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2B204A))) {
            Box(modifier = Modifier.fillMaxWidth(value.coerceIn(0, 100) / 100f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}

@Composable
private fun StarterPickerDialog(onPick: (PetDefinition) -> Unit) {
    Dialog(onDismissRequest = {}) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF21173E), modifier = Modifier.fillMaxWidth().fillMaxHeight(0.86f)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("🥚 Выбери стартового питомца!", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                Text("15 стартовых видов + ${PetCatalog.allPets.size - 15} героев и существ в капсулах!", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(8.dp))
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(PetCatalog.starters) { pet ->
                        Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF2E2252), modifier = Modifier.clickable { onPick(pet) }) {
                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                PixelPetAvatar(pet = pet, stage = PetStage.ADULT, palette = pet.defaultPalette, modifier = Modifier.size(56.dp))
                                Text(pet.name, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(pet.personality, color = Color(0xFFCBB8FF), fontFamily = FontFamily.Monospace, fontSize = 8.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GachaCapsuleDialog(
    coins: Int,
    unlockedIds: List<Int>,
    onDismiss: () -> Unit,
    onRoll: (GachaCapsule) -> Triple<PetDefinition, PetPalette, Boolean>?,
    onHatchInNewSlot: (PetDefinition, PetPalette) -> Unit
) {
    var result by remember { mutableStateOf<Triple<PetDefinition, PetPalette, Boolean>?>(null) }
    var spinning by remember { mutableStateOf(false) }
    var pendingCap by remember { mutableStateOf<GachaCapsule?>(null) }

    LaunchedEffect(pendingCap) {
        val cap = pendingCap ?: return@LaunchedEffect
        spinning = true
        delay(800)
        spinning = false
        result = onRoll(cap)
        pendingCap = null
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF21173E), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("🎰 Автомат Капсул", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    Text("🪙 $coins", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Text("Открыто видов: ${unlockedIds.size} / ${PetCatalog.allPets.size}", color = Color(0xFFCBB8FF), fontFamily = FontFamily.Monospace, fontSize = 11.sp)

                if (spinning) {
                    Text("✨ Крутим капсулу... ✨", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(vertical = 24.dp))
                } else {
                    result?.let { (wonPet, wonPal, isNew) ->
                        Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF33235E), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (isNew) "🎉 НОВЫЙ ПИТОМЕЦ!" else "✨ ПОВТОР (+кешбэк!)", color = Color(0xFF7BE495), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                PixelPetAvatar(wonPet, PetStage.ADULT, wonPal, isHappy = true, modifier = Modifier.size(88.dp))
                                Text("${wonPet.name} • ${wonPal.title}", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                BadgeChip(wonPet.rarity.title, Color(wonPet.rarity.colorHex), textDark = true)
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(onClick = { onHatchInNewSlot(wonPet, wonPal) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B894)), modifier = Modifier.fillMaxWidth()) {
                                    Text("🥚 Поселить в Отель и вылупить!", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                GachaCatalog.capsules.forEach { cap ->
                    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF2E2252), modifier = Modifier.fillMaxWidth().clickable(enabled = !spinning) { SoundEngine.playClick(); pendingCap = cap }) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(cap.emoji, fontSize = 26.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(cap.name, color = Color(cap.colorHex), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(cap.oddsText, color = Color(0xFFCBB8FF), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            }
                            Surface(shape = RoundedCornerShape(9.dp), color = Color(0xFF6C5CE7)) {
                                Text("${cap.price} 🪙", modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Закрыть", color = Color(0xFFFF85B3)) }
            }
        }
    }
}

@Composable
private fun ShopTabChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier = modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick), color = if (selected) Color(0xFF6C5CE7) else Color(0xFF2E2252)) {
        Text(label, modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun ShopItemRow(
    emoji: String,
    title: String,
    subtitle: String,
    price: Int,
    owned: Boolean,
    active: Boolean,
    badgeColor: Color,
    onClick: () -> Unit
) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF2E2252), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 20.sp)
            Spacer(modifier = Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = badgeColor, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            }
            val btnText = when {
                active -> "Надето ✓"
                owned -> "Надеть"
                price == 0 -> "Бесплатно"
                else -> "$price 🪙"
            }
            val btnCol = if (active) Color(0xFF00B894) else if (owned) Color(0xFF534085) else Color(0xFF6C5CE7)
            Surface(shape = RoundedCornerShape(8.dp), color = btnCol) {
                Text(btnText, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun PetCatalogDialog(
    currentPetId: Int,
    unlockedIds: List<Int>,
    onDismiss: () -> Unit,
    onOpenGacha: () -> Unit,
    onAdoptPet: (PetDefinition) -> Unit
) {
    var filterRarity by remember { mutableStateOf<PetRarity?>(null) }
    val filtered = remember(filterRarity) { if (filterRarity == null) PetCatalog.allPets else PetCatalog.allPets.filter { it.rarity == filterRarity } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF21173E), modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("🐾 Альманах (${unlockedIds.size}/${PetCatalog.allPets.size})", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFE84393), modifier = Modifier.clickable { onOpenGacha() }) {
                        Text("🎰 Капсулы", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChipItem("Все", filterRarity == null) { filterRarity = null }
                    PetRarity.entries.forEach { r -> FilterChipItem(r.title, filterRarity == r) { filterRarity = r } }
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(filtered) { item ->
                        val unlocked = item.id in unlockedIds
                        val isCurrent = item.id == currentPetId
                        Surface(shape = RoundedCornerShape(14.dp), color = if (isCurrent) Color(0xFF4B328A) else Color(0xFF2D2152), modifier = Modifier.clickable { if (unlocked) onAdoptPet(item) else onOpenGacha() }) {
                            Column(modifier = Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                PixelPetAvatar(item, PetStage.ADULT, item.defaultPalette, isSilhouette = !unlocked, modifier = Modifier.size(56.dp))
                                Text(if (unlocked) item.name else "???", color = if (unlocked) Color.White else Color(0xFF8E7CC3), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                BadgeChip(item.rarity.title, Color(item.rarity.colorHex), textDark = true)
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Закрыть", color = Color(0xFFFF85B3)) }
            }
        }
    }
}

@Composable
private fun FilterChipItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), color = if (selected) Color(0xFF6C5CE7) else Color(0xFF2E2252), modifier = Modifier.clickable(onClick = onClick)) {
        Text(label, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ProfileDialog(
    state: PetSaveState,
    pet: PetDefinition,
    palette: PetPalette,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onGrowFast: () -> Unit,
    onOpenMyPets: () -> Unit
) {
    var nameInput by remember { mutableStateOf(state.customName) }
    val favFood = FoodCatalog.items.find { it.id == pet.favoriteFoodId }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF21173E), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("📋 Паспорт питомца", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelPetAvatar(pet, state.stage(), palette, isSleeping = state.isSleeping, isSick = state.isSick, isDeceased = state.isDeceased, equipped = state.equipped(), modifier = Modifier.size(68.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("${pet.name} • ${palette.title}", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("Характер: ${pet.personality} • Привяз.: ${state.affection}", color = Color(0xFFCBB8FF), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        Text("Навыки: 🧠${state.skillIntellect} ⚡${state.skillAgility} 🌟${state.skillCharisma} 🎨${state.skillCreativity}", color = Color(0xFF9BF6FF), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        if (favFood != null) Text("Любит: ${favFood.emoji} ${favFood.name}", color = Color(0xFFFF85B3), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
                OutlinedTextField(value = nameInput, onValueChange = { if (it.length <= 16) nameInput = it }, label = { Text("Имя питомца") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { onRename(nameInput); onDismiss() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C5CE7))) { Text("Сохранить имя", fontFamily = FontFamily.Monospace) }
                Button(onClick = onGrowFast, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B894))) { Text("⚡ Ускорить рост (Тест стадий)", fontFamily = FontFamily.Monospace) }
                Button(onClick = onOpenMyPets, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE84393))) { Text("🏨 Мои питомцы (Отель и Спячка)", fontFamily = FontFamily.Monospace) }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Закрыть", color = Color(0xFFFF85B3)) }
            }
        }
    }
}

// =====================================================================
// ЭТАП 3: ЕЖЕДНЕВНЫЙ БОНУС, ФОТОАЛЬБОМ И ДОСТИЖЕНИЯ
// =====================================================================

@Composable
private fun DailyBonusDialog(
    account: AccountSaveState,
    onDismiss: () -> Unit,
    onClaim: (DailyRewardDef) -> Unit
) {
    val targetDay = account.nextDailyDayIndex()
    val todayReward = DailyRewardCatalog.rewards.find { it.day == targetDay } ?: DailyRewardCatalog.rewards.first()

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF21173E), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🎁 Ежедневный бонус (День $targetDay/7)", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                Text("Заходи каждый день — на 7-й день ждёт Легендарная капсула!", color = Color(0xFFCBB8FF), fontFamily = FontFamily.Monospace, fontSize = 10.sp, textAlign = TextAlign.Center)

                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().height(155.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(DailyRewardCatalog.rewards) { r ->
                        val isCurrent = r.day == targetDay
                        val isPast = r.day < targetDay
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                isCurrent -> Color(0xFF5D3FD3)
                                isPast -> Color(0xFF1F3B36)
                                else -> Color(0xFF2E2252)
                            },
                            modifier = Modifier.border(1.5.dp, if (isCurrent) Color(0xFFFFD166) else Color.Transparent, RoundedCornerShape(12.dp))
                        ) {
                            Column(modifier = Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Д${r.day}", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                Text(if (r.freeLegendRoll) "👑" else "🪙", fontSize = 18.sp)
                                Text("${r.coins}", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            }
                        }
                    }
                }

                Button(
                    onClick = { onClaim(todayReward) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B894))
                ) {
                    Text("🎉 Забрать +${todayReward.coins} 🪙!", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Позже", color = Color(0xFFFF85B3)) }
            }
        }
    }
}

@Composable
private fun AlbumAndAchievementsDialog(
    account: AccountSaveState,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    val photos = account.safePhotos()
    val unlockedAchs = account.safeAchievements().toSet()
    val df = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF21173E), modifier = Modifier.fillMaxWidth().fillMaxHeight(0.86f)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🏆 Альбом и Достижения", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShopTabChip("📸 Фото (${photos.size})", tab == 0, Modifier.weight(1f)) { tab = 0 }
                    ShopTabChip("🏆 Ачивки (${unlockedAchs.size}/${AchievementCatalog.all.size})", tab == 1, Modifier.weight(1f)) { tab = 1 }
                }

                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tab == 0) {
                        if (photos.isEmpty()) {
                            Text("Пока нет снимков! Нажми 📸 на экране комнаты, чтобы сделать памятное фото питомца!", color = Color(0xFFCBB8FF), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        photos.forEach { ph ->
                            val def = PetCatalog.getById(ph.petId)
                            val pal = PetPalette.byNameOrDefault(ph.paletteName, def.defaultPalette)
                            val st = try { PetStage.valueOf(ph.stageName) } catch (_: Throwable) { PetStage.ADULT }
                            Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF2E2252), modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PixelPetAvatar(def, st, pal, isHappy = true, equipped = ph.equipped, modifier = Modifier.size(62.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(ph.caption, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        Text("${ph.locationLabel} • ${ph.weatherLabel}", color = Color(0xFF9BF6FF), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                        Text(df.format(Date(ph.timestampMs)), color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                                    }
                                }
                            }
                        }
                    } else {
                        AchievementCatalog.all.forEach { ach ->
                            val done = ach.id in unlockedAchs
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (done) Color(0xFF254D3E) else Color(0xFF2E2252),
                                modifier = Modifier.fillMaxWidth().border(1.dp, if (done) Color(0xFF7BE495) else Color.Transparent, RoundedCornerShape(12.dp))
                            ) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (done) ach.emoji else "🔒", fontSize = 22.sp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(ach.title, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        Text(ach.desc, color = Color(0xFFCBB8FF), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                                    }
                                    Surface(shape = RoundedCornerShape(8.dp), color = if (done) Color(0xFF00B894) else Color(0xFF534085)) {
                                        Text(if (done) "✓" else "+${ach.rewardCoins}🪙", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Закрыть", color = Color(0xFFFF85B3)) }
            }
        }
    }
}
