package com.tamaverse.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.random.Random

enum class MiniGameType {
    NONE, CATCH, RUNNER, DIRECTION, MEMORY, SNAKE, WHACK, SIMON, QUIZ
}

data class MiniGameResult(
    val coinsEarned: Int,
    val moodGain: Int,
    val iqGain: Int,
    val weightDelta: Int = 0,
    val score: Int = 0,
    val gameType: MiniGameType
)

@Composable
fun GamesHubDialog(
    highCatch: Int,
    highRunner: Int,
    highSnake: Int,
    onDismiss: () -> Unit,
    onSelectGame: (MiniGameType) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF21173E),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.84f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "🎮 Игровой зал (8 игр!)",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GameCard("🍓", "Лови вкусняшки", "Аркада • Рекорд: $highCatch", Color(0xFFFF6B9D)) { onSelectGame(MiniGameType.CATCH) }
                    GameCard("🏃", "Пиксель-Раннер", "Прыжки + спорт • Рекорд: $highRunner", Color(0xFF00B894)) { onSelectGame(MiniGameType.RUNNER) }
                    GameCard("🐍", "Пиксель-Змейка", "Собирай ягодки • Рекорд: $highSnake", Color(0xFF7BE495)) { onSelectGame(MiniGameType.SNAKE) }
                    GameCard("🔨", "Поймай Слаймов", "На реакцию (20 сек)", Color(0xFFFF9F43)) { onSelectGame(MiniGameType.WHACK) }
                    GameCard("🎵", "Музыкальный Саймон", "Повтори мелодию + IQ", Color(0xFF00E5FF)) { onSelectGame(MiniGameType.SIMON) }
                    GameCard("🧮", "Школьная викторина", "Быстрая логика • +8 IQ!", Color(0xFFFFD166)) { onSelectGame(MiniGameType.QUIZ) }
                    GameCard("🧭", "Угадай направление", "Классика Тамагочи (5 раундов)", Color(0xFF6C5CE7)) { onSelectGame(MiniGameType.DIRECTION) }
                    GameCard("🃏", "Пиксель-Пары", "Мемори • Прокачка IQ", Color(0xFF0984E3)) { onSelectGame(MiniGameType.MEMORY) }
                }
                Spacer(modifier = Modifier.height(6.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Закрыть", color = Color(0xFFFF85B3))
                }
            }
        }
    }
}

@Composable
private fun GameCard(emoji: String, title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF302359),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, color, RoundedCornerShape(14.dp))
            .clickable { SoundEngine.playClick(); onClick() }
    ) {
        Row(modifier = Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 26.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(subtitle, color = Color(0xFFC4B5FD), fontSize = 11.sp)
            }
            Text("▶", color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ==========================================
// 1. ЛОВИ ВКУСНЯШКИ
// ==========================================
private data class FallingItem(val id: Int, val lane: Int, val y: Float, val isBomb: Boolean, val isStar: Boolean)

@Composable
fun CatchMiniGameDialog(
    pet: PetDefinition,
    palette: PetPalette,
    hatId: String?,
    accId: String?,
    onFinish: (MiniGameResult) -> Unit,
    onDismiss: () -> Unit
) {
    var playerLane by remember { mutableIntStateOf(2) }
    var score by remember { mutableIntStateOf(0) }
    var lives by remember { mutableIntStateOf(3) }
    var timeLeft by remember { mutableIntStateOf(25) }
    var items by remember { mutableStateOf(listOf<FallingItem>()) }
    var finished by remember { mutableStateOf(false) }
    var nextId by remember { mutableIntStateOf(1) }

    LaunchedEffect(finished) {
        if (finished) return@LaunchedEffect
        var tick = 0
        while (!finished && lives > 0 && timeLeft > 0) {
            delay(50)
            tick++
            if (tick % 20 == 0) timeLeft--
            if (tick % 11 == 0) {
                val r = Random.nextInt(100)
                items = items + FallingItem(nextId++, Random.nextInt(5), 0f, r < 24, r >= 85)
            }
            val nextList = mutableListOf<FallingItem>()
            for (it in items) {
                val ny = it.y + 0.042f
                if (ny in 0.78f..0.93f && it.lane == playerLane) {
                    if (it.isBomb) {
                        lives--
                        SoundEngine.playSleep()
                    } else {
                        score += if (it.isStar) 25 else 10
                        SoundEngine.playHappy()
                    }
                } else if (ny < 1.0f) {
                    nextList.add(it.copy(y = ny))
                }
            }
            items = nextList
            if (lives <= 0 || timeLeft <= 0) finished = true
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF1F163B), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("🍓 Счёт: $score", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("⏱ ${timeLeft}с • ${"❤".repeat(lives.coerceAtLeast(0))}", color = Color(0xFFFF6B9D), fontFamily = FontFamily.Monospace)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF2B2054))
                        .border(2.dp, Color(0xFF5D46A6), RoundedCornerShape(16.dp))
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        for (lane in 0..4) Box(modifier = Modifier.weight(1f).fillMaxHeight().clickable { playerLane = lane })
                    }
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Пиксельное небо, облачка и трава!
                        drawRect(Color(0xFF1E1642), Offset.Zero, size)
                        drawRect(Color(0xFF28205C), Offset(0f, size.height * 0.28f), Size(size.width, size.height * 0.4f))
                        drawRect(Color(0xFF2D6A4F), Offset(0f, size.height * 0.86f), Size(size.width, size.height * 0.14f))
                        drawRect(Color(0xFF52B788), Offset(0f, size.height * 0.86f), Size(size.width, 6f))
                        // Звёздочки на фоне
                        for (sx in 1..6) {
                            drawRect(Color(0x66FFF3B0), Offset(sx * size.width / 7f, (sx * 37 % 90).toFloat() + 14f), Size(5f, 5f))
                        }

                        val laneW = size.width / 5f
                        items.forEach { item ->
                            val cx = item.lane * laneW + laneW / 2f
                            val cy = item.y * size.height
                            val p = 4.5f
                            when {
                                item.isBomb -> {
                                    // Пиксельная бомбочка с контуром и искрой
                                    drawRect(Color(0xFF0E0E1A), Offset(cx - 3.5f * p, cy - 3.5f * p), Size(7 * p, 7 * p))
                                    drawRect(Color(0xFF2B2D42), Offset(cx - 2.5f * p, cy - 2.5f * p), Size(5 * p, 5 * p))
                                    drawRect(Color(0xFF8D99AE), Offset(cx - 1.5f * p, cy - 1.5f * p), Size(1.5f * p, 1.5f * p))
                                    drawRect(Color(0xFFFFD166), Offset(cx - 0.5f * p, cy - 5f * p), Size(p, 1.5f * p))
                                    drawRect(Color(0xFFFF4D4D), Offset(cx + 0.5f * p, cy - 6f * p), Size(1.5f * p, 1.5f * p))
                                }
                                item.isStar -> {
                                    // Золотая пиксельная звезда с бликом
                                    drawRect(Color(0xFF59321E), Offset(cx - 4f * p, cy - 2f * p), Size(8 * p, 4 * p))
                                    drawRect(Color(0xFF59321E), Offset(cx - 2f * p, cy - 4f * p), Size(4 * p, 8 * p))
                                    drawRect(Color(0xFFFFD166), Offset(cx - 3f * p, cy - 1.5f * p), Size(6 * p, 3 * p))
                                    drawRect(Color(0xFFFFD166), Offset(cx - 1.5f * p, cy - 3f * p), Size(3 * p, 6 * p))
                                    drawRect(Color.White, Offset(cx - p, cy - 2f * p), Size(1.5f * p, 1.5f * p))
                                }
                                else -> {
                                    // Сочная пиксельная клубничка с листиком и семечками
                                    drawRect(Color(0xFF421226), Offset(cx - 3.5f * p, cy - 2f * p), Size(7 * p, 6 * p))
                                    drawRect(Color(0xFFFF4D8D), Offset(cx - 2.5f * p, cy - 1.5f * p), Size(5 * p, 4.5f * p))
                                    drawRect(Color(0xFF2ECC71), Offset(cx - 2f * p, cy - 3.2f * p), Size(4 * p, 1.7f * p))
                                    drawRect(Color(0xFFFFE066), Offset(cx - 1.5f * p, cy), Size(p, p))
                                    drawRect(Color(0xFFFFE066), Offset(cx + 0.5f * p, cy + p), Size(p, p))
                                }
                            }
                        }
                    }
                    Row(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 4.dp)) {
                        for (lane in 0..4) {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                if (lane == playerLane) {
                                    PixelPetAvatar(pet, PetStage.ADULT, palette, isHappy = true, hatId = hatId, accId = accId, modifier = Modifier.size(54.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                if (finished) {
                    val coins = (score / 3).coerceIn(8, 95)
                    Text("Награда: +$coins 🪙 и +15 ♥", color = Color(0xFF7BE495), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { onFinish(MiniGameResult(coins, 15, 2, 0, score, MiniGameType.CATCH)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B894)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Забрать награду!", fontFamily = FontFamily.Monospace) }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { playerLane = (playerLane - 1).coerceAtLeast(0) }, modifier = Modifier.weight(1f).height(46.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C5CE7))) { Text("◀ ВЛЕВО") }
                        Button(onClick = { playerLane = (playerLane + 1).coerceAtMost(4) }, modifier = Modifier.weight(1f).height(46.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C5CE7))) { Text("ВПРАВО ▶") }
                    }
                }
            }
        }
    }
}

// ==========================================
// 2. ПИКСЕЛЬ-РАННЕР
// ==========================================
private data class RunnerObstacle(val x: Float, val isCoin: Boolean)

@Composable
fun RunnerMiniGameDialog(
    pet: PetDefinition,
    palette: PetPalette,
    hatId: String?,
    accId: String?,
    onFinish: (MiniGameResult) -> Unit,
    onDismiss: () -> Unit
) {
    var petY by remember { mutableFloatStateOf(0f) }
    var velY by remember { mutableFloatStateOf(0f) }
    var score by remember { mutableIntStateOf(0) }
    var obstacles by remember { mutableStateOf(listOf<RunnerObstacle>()) }
    var gameOver by remember { mutableStateOf(false) }

    fun jump() {
        if (!gameOver && petY <= 0.01f) {
            velY = 0.095f
            SoundEngine.playClick()
        }
    }

    LaunchedEffect(gameOver) {
        if (gameOver) return@LaunchedEffect
        var tick = 0
        while (!gameOver) {
            delay(33)
            tick++
            if (tick % 5 == 0) score++
            petY += velY
            if (petY > 0f) velY -= 0.0085f else { petY = 0f; velY = 0f }
            if (tick % 32 == 0) obstacles = obstacles + RunnerObstacle(1.08f, Random.nextInt(100) < 35)
            val speed = (0.024f + (score / 1200f)).coerceAtMost(0.042f)
            val updated = mutableListOf<RunnerObstacle>()
            for (ob in obstacles) {
                val nx = ob.x - speed
                if (abs(nx - 0.22f) < 0.07f) {
                    if (ob.isCoin && petY > 0.14f) {
                        score += 15
                        SoundEngine.playHappy()
                        continue
                    } else if (!ob.isCoin && petY < 0.18f) {
                        gameOver = true
                        SoundEngine.playSleep()
                        break
                    }
                }
                if (nx > -0.1f) updated.add(ob.copy(x = nx))
            }
            obstacles = updated
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF1F163B), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🏃 Пиксель-Раннер • Счёт: $score", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF70D6FF))
                        .border(2.dp, Color(0xFF00B894), RoundedCornerShape(16.dp))
                        .clickable { jump() }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val groundY = size.height * 0.78f
                        // Пиксельное небо, облака и холмы
                        drawRect(Color(0xFF56CCF2), Offset.Zero, Size(size.width, groundY))
                        drawRect(Color(0xFF81E1FF), Offset(0f, groundY * 0.45f), Size(size.width, groundY * 0.55f))
                        drawRect(Color(0xFFFFF3B0), Offset(size.width - 54f, 18f), Size(28f, 28f))
                        drawRect(Color.White, Offset(36f, 26f), Size(46f, 14f))
                        drawRect(Color.White, Offset(48f, 18f), Size(24f, 10f))
                        drawRect(Color(0xFF6BCB77), Offset(0f, groundY - 22f), Size(size.width * 0.45f, 22f))
                        drawRect(Color(0xFF4D96FF).copy(alpha = 0.25f), Offset(size.width * 0.5f, groundY - 16f), Size(size.width * 0.5f, 16f))
                        // Дорожка с пиксельной травой
                        drawRect(Color(0xFFDDA15E), Offset(0f, groundY), Size(size.width, size.height - groundY))
                        drawRect(Color(0xFF2ECC71), Offset(0f, groundY), Size(size.width, 12f))
                        drawRect(Color(0xFF1B4332), Offset(0f, groundY), Size(size.width, 3f))
                        obstacles.forEach { ob ->
                            val ox = ob.x * size.width
                            if (ob.isCoin) {
                                val cy = groundY - size.height * 0.36f
                                drawRect(Color(0xFF59321E), Offset(ox - 15f, cy - 15f), Size(30f, 30f))
                                drawRect(Color(0xFFFFD166), Offset(ox - 12f, cy - 12f), Size(24f, 24f))
                                drawRect(Color(0xFFFFA801), Offset(ox - 5f, cy - 7f), Size(10f, 14f))
                                drawRect(Color.White, Offset(ox - 9f, cy - 9f), Size(5f, 5f))
                            } else {
                                // Кактус с контуром и цветочком
                                drawRect(Color(0xFF0E3820), Offset(ox - 12f, groundY - 42f), Size(24f, 42f))
                                drawRect(Color(0xFF2A9D8F), Offset(ox - 9f, groundY - 39f), Size(18f, 39f))
                                drawRect(Color(0xFF0E3820), Offset(ox - 20f, groundY - 28f), Size(9f, 16f))
                                drawRect(Color(0xFF2A9D8F), Offset(ox - 17f, groundY - 25f), Size(6f, 10f))
                                drawRect(Color(0xFF0E3820), Offset(ox + 11f, groundY - 24f), Size(9f, 14f))
                                drawRect(Color(0xFF2A9D8F), Offset(ox + 12f, groundY - 21f), Size(6f, 8f))
                                drawRect(Color(0xFFFF4D8D), Offset(ox - 4f, groundY - 46f), Size(8f, 5f))
                            }
                        }
                    }
                    Box(modifier = Modifier.align(Alignment.BottomStart).padding(start = 36.dp, bottom = (44f + petY * 220f).dp)) {
                        PixelPetAvatar(pet, PetStage.ADULT, palette, isHappy = !gameOver, isSick = gameOver, hatId = hatId, accId = accId, modifier = Modifier.size(56.dp))
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                if (gameOver) {
                    val coins = (score / 3).coerceIn(8, 105)
                    Text("Финиш! +$coins 🪙 • Спорт: -1 кг!", color = Color(0xFF7BE495), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { onFinish(MiniGameResult(coins, 15, 1, -1, score, MiniGameType.RUNNER)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B894)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Забрать награду!", fontFamily = FontFamily.Monospace) }
                } else {
                    Button(onClick = { jump() }, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B894))) {
                        Text("⬆ ПРЫЖОК!", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ==========================================
// 3. ПИКСЕЛЬ-ЗМЕЙКА (НОВАЯ!)
// ==========================================
@Composable
fun SnakeMiniGameDialog(
    onFinish: (MiniGameResult) -> Unit,
    onDismiss: () -> Unit
) {
    val gridSize = 11
    var snake by remember { mutableStateOf(listOf(5 to 5, 4 to 5, 3 to 5)) }
    var dir by remember { mutableStateOf(1 to 0) }
    var food by remember { mutableStateOf(8 to 5) }
    var score by remember { mutableIntStateOf(0) }
    var gameOver by remember { mutableStateOf(false) }

    LaunchedEffect(gameOver) {
        if (gameOver) return@LaunchedEffect
        while (!gameOver) {
            delay(210)
            val head = snake.first()
            val next = (head.first + dir.first) to (head.second + dir.second)
            if (next.first !in 0 until gridSize || next.second !in 0 until gridSize || next in snake) {
                gameOver = true
                SoundEngine.playSleep()
                break
            }
            if (next == food) {
                score += 10
                SoundEngine.playHappy()
                snake = listOf(next) + snake
                var nf = Random.nextInt(gridSize) to Random.nextInt(gridSize)
                var guard = 0
                while (nf in snake && guard < 40) {
                    nf = Random.nextInt(gridSize) to Random.nextInt(gridSize)
                    guard++
                }
                food = nf
            } else {
                snake = listOf(next) + snake.dropLast(1)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF1F163B), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🐍 Пиксель-Змейка • Счёт: $score", color = Color(0xFF7BE495), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .size(230.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF141029))
                        .border(2.dp, Color(0xFF7BE495), RoundedCornerShape(14.dp))
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cell = size.width / gridSize
                        // Шахматная лужайка
                        for (gy in 0 until gridSize) {
                            for (gx in 0 until gridSize) {
                                val bg = if ((gx + gy) % 2 == 0) Color(0xFF1A2E2A) else Color(0xFF203833)
                                drawRect(bg, Offset(gx * cell, gy * cell), Size(cell + 0.5f, cell + 0.5f))
                            }
                        }
                        // Пиксельная ягодка с контуром и листиком
                        val fx = food.first * cell
                        val fy = food.second * cell
                        drawRect(Color(0xFF421226), Offset(fx + 2f, fy + 4f), Size(cell - 4f, cell - 6f))
                        drawRect(Color(0xFFFF4D8D), Offset(fx + 4f, fy + 6f), Size(cell - 8f, cell - 10f))
                        drawRect(Color(0xFF52B788), Offset(fx + cell * 0.35f, fy + 2f), Size(cell * 0.3f, 5f))
                        // Змейка с контуром и глазками на голове
                        snake.forEachIndexed { idx, p ->
                            val sx = p.first * cell
                            val sy = p.second * cell
                            drawRect(Color(0xFF0E291B), Offset(sx + 1f, sy + 1f), Size(cell - 2f, cell - 2f))
                            val col = if (idx == 0) Color(0xFF7BE495) else if (idx % 2 == 0) Color(0xFF2ECC71) else Color(0xFF40C077)
                            drawRect(col, Offset(sx + 3f, sy + 3f), Size(cell - 6f, cell - 6f))
                            if (idx == 0) {
                                drawRect(Color.White, Offset(sx + 5f, sy + 5f), Size(4f, 4f))
                                drawRect(Color.White, Offset(sx + cell - 9f, sy + 5f), Size(4f, 4f))
                                drawRect(Color(0xFF141414), Offset(sx + 6f, sy + 6f), Size(2f, 2f))
                                drawRect(Color(0xFF141414), Offset(sx + cell - 8f, sy + 6f), Size(2f, 2f))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                if (gameOver) {
                    val coins = (score / 2).coerceIn(8, 100)
                    Text("Награда: +$coins 🪙 и +15 ♥", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { onFinish(MiniGameResult(coins, 15, 3, 0, score, MiniGameType.SNAKE)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B894)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Забрать награду!", fontFamily = FontFamily.Monospace) }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { if (dir.second != 1) dir = 0 to -1 }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5F27CD))) { Text("⬆") }
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Button(onClick = { if (dir.first != 1) dir = -1 to 0 }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5F27CD))) { Text("⬅") }
                            Button(onClick = { if (dir.first != -1) dir = 1 to 0 }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5F27CD))) { Text("➡") }
                        }
                        Button(onClick = { if (dir.second != -1) dir = 0 to 1 }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5F27CD))) { Text("⬇") }
                    }
                }
            }
        }
    }
}

// ==========================================
// 4. ПОЙМАЙ СЛАЙМОВ (НА РЕАКЦИЮ — НОВАЯ!)
// ==========================================
@Composable
fun WhackSlimeMiniGameDialog(
    onFinish: (MiniGameResult) -> Unit,
    onDismiss: () -> Unit
) {
    // 0 = пусто, 1 = зелёный слайм (+10), 2 = золотой (+25), 3 = бомба (-15)
    var board by remember { mutableStateOf(List(9) { 0 }) }
    var score by remember { mutableIntStateOf(0) }
    var timeLeft by remember { mutableIntStateOf(20) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(finished) {
        if (finished) return@LaunchedEffect
        while (!finished && timeLeft > 0) {
            val next = MutableList(9) { 0 }
            val count = Random.nextInt(1, 3)
            repeat(count) {
                val pos = Random.nextInt(9)
                val r = Random.nextInt(100)
                next[pos] = when {
                    r < 22 -> 3
                    r > 82 -> 2
                    else -> 1
                }
            }
            board = next
            delay(750)
            timeLeft--
            if (timeLeft <= 0) finished = true
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF1F163B), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🔨 Поймай Слаймов • ⏱${timeLeft}с • $score", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (r in 0..2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (c in 0..2) {
                                val idx = r * 3 + c
                                val cellVal = board[idx]
                                Box(
                                    modifier = Modifier
                                        .size(76.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (cellVal == 0) Color(0xFF203833) else Color(0xFF2D6A4F))
                                        .border(2.dp, Color(0xFF52B788), RoundedCornerShape(14.dp))
                                        .clickable(enabled = !finished && cellVal != 0) {
                                            when (cellVal) {
                                                1 -> { score += 10; SoundEngine.playClick() }
                                                2 -> { score += 25; SoundEngine.playHappy() }
                                                3 -> { score = (score - 15).coerceAtLeast(0); SoundEngine.playSleep() }
                                            }
                                            board = board.toMutableList().also { it[idx] = 0 }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                                        val w = size.width
                                        val h = size.height
                                        // Пиксельная норка в траве
                                        drawRect(Color(0xFF141029), Offset(w * 0.14f, h * 0.68f), Size(w * 0.72f, h * 0.22f))
                                        when (cellVal) {
                                            1, 2 -> {
                                                val bodyCol = if (cellVal == 2) Color(0xFFFFD166) else Color(0xFF7BE495)
                                                val outlineCol = if (cellVal == 2) Color(0xFF59321E) else Color(0xFF143632)
                                                drawRect(outlineCol, Offset(w * 0.2f, h * 0.25f), Size(w * 0.6f, h * 0.52f))
                                                drawRect(bodyCol, Offset(w * 0.24f, h * 0.30f), Size(w * 0.52f, h * 0.42f))
                                                drawRect(Color.White, Offset(w * 0.29f, h * 0.34f), Size(w * 0.12f, h * 0.1f))
                                                drawRect(outlineCol, Offset(w * 0.32f, h * 0.45f), Size(w * 0.1f, h * 0.12f))
                                                drawRect(outlineCol, Offset(w * 0.58f, h * 0.45f), Size(w * 0.1f, h * 0.12f))
                                                if (cellVal == 2) {
                                                    drawRect(Color(0xFFFF4D8D), Offset(w * 0.36f, h * 0.16f), Size(w * 0.28f, h * 0.12f))
                                                }
                                            }
                                            3 -> {
                                                // Пиксельная бомба
                                                drawRect(Color(0xFF0E0E1A), Offset(w * 0.26f, h * 0.3f), Size(w * 0.48f, h * 0.48f))
                                                drawRect(Color(0xFF3A3D5C), Offset(w * 0.32f, h * 0.36f), Size(w * 0.36f, h * 0.36f))
                                                drawRect(Color(0xFFFF4D4D), Offset(w * 0.46f, h * 0.16f), Size(w * 0.14f, h * 0.14f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (finished) {
                    val coins = (score / 3).coerceIn(10, 95)
                    Text("Награда: +$coins 🪙 и +15 ♥", color = Color(0xFF7BE495), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { onFinish(MiniGameResult(coins, 15, 2, 0, score, MiniGameType.WHACK)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B894)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Забрать награду!", fontFamily = FontFamily.Monospace) }
                } else {
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Выйти", color = Color(0xFFFF85B3)) }
                }
            }
        }
    }
}

// ==========================================
// 5. МУЗЫКАЛЬНЫЙ САЙМОН (НОВАЯ!)
// ==========================================
@Composable
fun SimonMiniGameDialog(
    onFinish: (MiniGameResult) -> Unit,
    onDismiss: () -> Unit
) {
    var sequence by remember { mutableStateOf(listOf(Random.nextInt(4), Random.nextInt(4))) }
    var userStep by remember { mutableIntStateOf(0) }
    var litPad by remember { mutableIntStateOf(-1) }
    var showing by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Слушай мелодию...") }
    var finished by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(1) }

    fun playPadSound(pad: Int) {
        when (pad) {
            0 -> SoundEngine.playClick()
            1 -> SoundEngine.playEat()
            2 -> SoundEngine.playBath()
            else -> SoundEngine.playHappy()
        }
    }

    LaunchedEffect(sequence, showing) {
        if (!showing || finished) return@LaunchedEffect
        status = "Запоминай! (Раунд $round/5)"
        delay(450)
        for (pad in sequence) {
            litPad = pad
            playPadSound(pad)
            delay(420)
            litPad = -1
            delay(200)
        }
        showing = false
        status = "Твоя очередь! Повтори!"
    }

    val padColors = listOf(Color(0xFFFF5252), Color(0xFF00E5FF), Color(0xFF7BE495), Color(0xFFFFD166))
    val padEmojis = listOf("🔴", "🔵", "🟢", "🟡")

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF1F163B), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🎵 Музыкальный Саймон", color = Color(0xFF00E5FF), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(status, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp)

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (r in 0..1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (c in 0..1) {
                                val pad = r * 2 + c
                                val isLit = litPad == pad
                                Box(
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (isLit) padColors[pad] else padColors[pad].copy(alpha = 0.35f))
                                        .border(2.5.dp, padColors[pad], RoundedCornerShape(18.dp))
                                        .clickable(enabled = !showing && !finished) {
                                            playPadSound(pad)
                                            if (pad == sequence[userStep]) {
                                                userStep++
                                                if (userStep >= sequence.size) {
                                                    if (round >= 5) {
                                                        finished = true
                                                        status = "Супер слух! Победа! ★"
                                                    } else {
                                                        round++
                                                        userStep = 0
                                                        sequence = sequence + Random.nextInt(4)
                                                        showing = true
                                                    }
                                                }
                                            } else {
                                                finished = true
                                                status = "Ой! Ошибочка на раунде $round!"
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(padEmojis[pad], fontSize = 32.sp)
                                }
                            }
                        }
                    }
                }

                if (finished) {
                    val coins = round * 16
                    Text("Награда: +$coins 🪙 и +5 IQ! 🧠", color = Color(0xFF7BE495), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { onFinish(MiniGameResult(coins, 14, 5, 0, round, MiniGameType.SIMON)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B894)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Забрать награду!", fontFamily = FontFamily.Monospace) }
                } else {
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Выйти", color = Color(0xFFFF85B3)) }
                }
            }
        }
    }
}

// ==========================================
// 6. ШКОЛЬНАЯ ВИКТОРИНА (НОВАЯ — БОЛЬШОЙ IQ!)
// ==========================================
private data class QuizQ(val prompt: String, val options: List<String>, val answerIdx: Int)

@Composable
fun QuizMiniGameDialog(
    onFinish: (MiniGameResult) -> Unit,
    onDismiss: () -> Unit
) {
    val questions = remember {
        buildList {
            repeat(6) {
                val a = Random.nextInt(3, 18)
                val b = Random.nextInt(2, 15)
                val isAdd = Random.nextBoolean()
                val correct = if (isAdd) a + b else a * b
                val prompt = if (isAdd) "$a + $b = ?" else "$a × $b = ?"
                val wrong = mutableSetOf<Int>()
                while (wrong.size < 3) {
                    val w = correct + Random.nextInt(-8, 9)
                    if (w != correct && w > 0) wrong.add(w)
                }
                val opts = (wrong.toList() + correct).shuffled()
                add(QuizQ(prompt, opts.map { it.toString() }, opts.indexOf(correct)))
            }
        }
    }

    var idx by remember { mutableIntStateOf(0) }
    var correctCount by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF21173E), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("🧮 Викторина • ${idx + 1}/${questions.size}", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)

                if (!finished) {
                    val q = questions[idx]
                    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF32245E), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = q.prompt,
                            modifier = Modifier.padding(20.dp),
                            textAlign = TextAlign.Center,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 26.sp
                        )
                    }
                    q.options.chunked(2).forEachIndexed { rowIdx, pair ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEachIndexed { colIdx, opt ->
                                val optIndex = rowIdx * 2 + colIdx
                                Button(
                                    onClick = {
                                        if (optIndex == q.answerIdx) {
                                            correctCount++
                                            SoundEngine.playHappy()
                                        } else {
                                            SoundEngine.playSleep()
                                        }
                                        if (idx + 1 >= questions.size) finished = true else idx++
                                    },
                                    modifier = Modifier.weight(1f).height(50.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C5CE7))
                                ) {
                                    Text(opt, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                }
                            }
                        }
                    }
                } else {
                    val coins = correctCount * 14 + 10
                    val iq = correctCount * 2 + 2
                    Text("Правильно: $correctCount из ${questions.size}!", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                    Text("Награда: +$coins 🪙 и +$iq IQ! 🧠", color = Color(0xFF7BE495), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { onFinish(MiniGameResult(coins, 12, iq, 0, correctCount, MiniGameType.QUIZ)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B894)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Забрать награду!", fontFamily = FontFamily.Monospace) }
                }
            }
        }
    }
}

// ==========================================
// 7. УГАДАЙ НАПРАВЛЕНИЕ
// ==========================================
@Composable
fun DirectionMiniGameDialog(
    pet: PetDefinition,
    palette: PetPalette,
    hatId: String?,
    accId: String?,
    onFinish: (MiniGameResult) -> Unit,
    onDismiss: () -> Unit
) {
    var round by remember { mutableIntStateOf(1) }
    var wins by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf("Куда посмотрит питомец?") }
    var finished by remember { mutableStateOf(false) }

    fun pick(choice: Int) {
        if (finished) return
        val secret = Random.nextInt(3)
        val dirNames = listOf("⬅️ Влево", "⬆️ Вверх", "➡️ Вправо")
        if (choice == secret) {
            wins++
            SoundEngine.playHappy()
            feedback = "Угадал! ${dirNames[secret]} ★"
        } else {
            SoundEngine.playClick()
            feedback = "Он выбрал ${dirNames[secret]}!"
        }
        if (round >= 5) finished = true else round++
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF21173E), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🧭 Раунд $round/5 • Побед: $wins", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                PixelPetAvatar(pet, PetStage.ADULT, palette, isHappy = wins > 0, hatId = hatId, accId = accId, modifier = Modifier.size(110.dp))
                Text(feedback, color = Color.White, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                if (finished) {
                    val coins = wins * 14 + 8
                    Text("Награда: +$coins 🪙 • +3 IQ!", color = Color(0xFF7BE495), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { onFinish(MiniGameResult(coins, 14, 3, 0, wins, MiniGameType.DIRECTION)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B894)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Забрать награду!", fontFamily = FontFamily.Monospace) }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pick(0) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C5CE7))) { Text("⬅️") }
                        Button(onClick = { pick(1) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE84393))) { Text("⬆️") }
                        Button(onClick = { pick(2) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0984E3))) { Text("➡️") }
                    }
                }
            }
        }
    }
}

// ==========================================
// 8. ПИКСЕЛЬ-ПАРЫ (МЕМОРИ)
// ==========================================
@Composable
fun MemoryMiniGameDialog(
    onFinish: (MiniGameResult) -> Unit,
    onDismiss: () -> Unit
) {
    val deck = remember { (listOf("🍓", "🍰", "🍣", "👑", "⭐", "💎") + listOf("🍓", "🍰", "🍣", "👑", "⭐", "💎")).shuffled() }
    var matched by remember { mutableStateOf(setOf<Int>()) }
    var openCards by remember { mutableStateOf(listOf<Int>()) }
    var moves by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(false) }

    LaunchedEffect(openCards) {
        if (openCards.size == 2) {
            locked = true
            moves++
            val (a, b) = openCards
            if (deck[a] == deck[b]) {
                SoundEngine.playHappy()
                matched = matched + a + b
                openCards = emptyList()
                locked = false
            } else {
                delay(650)
                openCards = emptyList()
                locked = false
            }
        }
    }

    val won = matched.size == deck.size

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF21173E), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🃏 Пиксель-Пары • Ходов: $moves", color = Color(0xFFFFD166), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (r in 0 until 3) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (c in 0 until 4) {
                                val idx = r * 4 + c
                                val isShown = idx in matched || idx in openCards
                                Box(
                                    modifier = Modifier
                                        .size(62.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (idx in matched) Color(0xFF1F6E58) else if (isShown) Color(0xFF5D46A6) else Color(0xFF33255E))
                                        .border(1.5.dp, Color(0xFF8C7AE6), RoundedCornerShape(12.dp))
                                        .clickable(enabled = !locked && !isShown) {
                                            SoundEngine.playClick()
                                            openCards = openCards + idx
                                        },
                                    contentAlignment = Alignment.Center
                                ) { Text(if (isShown) deck[idx] else "❓", fontSize = 26.sp) }
                            }
                        }
                    }
                }
                if (won) {
                    val coins = (70 - moves * 2).coerceIn(25, 65)
                    Text("Победа! +$coins 🪙 и +6 IQ! 🧠", color = Color(0xFF7BE495), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { onFinish(MiniGameResult(coins, 16, 6, 0, moves, MiniGameType.MEMORY)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B894)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Забрать награду!", fontFamily = FontFamily.Monospace) }
                } else {
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Выйти", color = Color(0xFFFF85B3)) }
                }
            }
        }
    }
}
