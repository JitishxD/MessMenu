package me.jitish.messmenu

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import me.jitish.messmenu.ui.theme.MessMenuTheme

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// ======== DATA CLASSES ========
/**
 * Represents a meal with its type, timing, and menu items.
 */
data class Meal(
    val type: MealType,
    val menuItems: List<String>
)

/**
 * Enum representing different meal types with their timings.
 * Timings are strictly defined as per mess schedule.
 */
enum class MealType(
    val displayName: String,
    val startTime: LocalTime,
    val endTime: LocalTime
) {
    BREAKFAST("Breakfast", LocalTime.of(8, 0), LocalTime.of(9, 0)),
    LUNCH("Lunch", LocalTime.of(12, 30), LocalTime.of(14, 0)),
    HIGH_TEA("High Tea", LocalTime.of(17, 0), LocalTime.of(18, 0)),
    DINNER("Dinner", LocalTime.of(19, 30), LocalTime.of(21, 0));

    /**
     * Returns formatted timing string (e.g., "08:00 – 09:00")
     */
    fun getTimingString(): String {
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        return "${startTime.format(formatter)} – ${endTime.format(formatter)}"
    }

    /**
     * Checks if the current time falls within this meal's serving window.
     */
    fun isCurrentlyServing(currentTime: LocalTime): Boolean {
        return !currentTime.isBefore(startTime) && !currentTime.isAfter(endTime)
    }
}

/**
 * Result of meal detection - either currently serving or upcoming.
 */
data class MealStatus(
    val isNowServing: Boolean,
    val meal: Meal,
    val dayName: String
)

// ======== JSON PARSING ========
/**
 * Loads and parses mess menu data from assets/messData.json.
 * Returns a map of MealType to a map of day names to menu items.
 */
fun loadMessData(context: Context): Map<MealType, Map<String, List<String>>> {
    return try {
        // Read JSON file from assets
        val jsonString = context.assets.open("messData.json").bufferedReader().use { it.readText() }
        val jsonObject = JSONObject(jsonString)
        val mealsObject = jsonObject.getJSONObject("meals")

        // Parse each meal type
        val result = mutableMapOf<MealType, Map<String, List<String>>>()

        // Map JSON keys to MealType enum
        val mealMapping = mapOf(
            "breakfast" to MealType.BREAKFAST,
            "lunch" to MealType.LUNCH,
            "high_tea" to MealType.HIGH_TEA,
            "dinner" to MealType.DINNER
        )

        mealMapping.forEach { (jsonKey, mealType) ->
            val mealObj = mealsObject.getJSONObject(jsonKey)
            val dayMenu = mutableMapOf<String, List<String>>()

            // Parse each day's menu
            mealObj.keys().forEach { day ->
                val itemsArray = mealObj.getJSONArray(day)
                val items = mutableListOf<String>()
                for (i in 0 until itemsArray.length()) {
                    items.add(itemsArray.getString(i))
                }
                dayMenu[day] = items
            }
            result[mealType] = dayMenu
        }

        result
    } catch (@Suppress("UNUSED_PARAMETER") e: Exception) {
        // Return empty map if parsing fails
        emptyMap()
    }
}

// ======== MEAL DETECTION LOGIC ========
/**
 * Determines the current or upcoming meal based on time and day.
 *
 * Logic:
 * 1. Check if any meal is currently being served
 * 2. If not, find the next upcoming meal
 * 3. After dinner (21:00), show next day's breakfast
 */
fun getMealStatus(
    messData: Map<MealType, Map<String, List<String>>>,
    currentTime: LocalTime = LocalTime.now(),
    currentDate: LocalDate = LocalDate.now()
): MealStatus? {
    if (messData.isEmpty()) return null

    val currentDayName = currentDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    // Check if any meal is currently being served
    for (mealType in MealType.entries) {
        if (mealType.isCurrentlyServing(currentTime)) {
            val menuItems = messData[mealType]?.get(currentDayName) ?: emptyList()
            return MealStatus(
                isNowServing = true,
                meal = Meal(mealType, menuItems),
                dayName = currentDayName
            )
        }
    }

    // No meal is currently serving - find the next upcoming meal
    return findNextMeal(messData, currentTime, currentDate)
}

/**
 * Finds the next upcoming meal.
 * If current time is after dinner, returns next day's breakfast.
 */
private fun findNextMeal(
    messData: Map<MealType, Map<String, List<String>>>,
    currentTime: LocalTime,
    currentDate: LocalDate
): MealStatus {
    val mealOrder = listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.HIGH_TEA, MealType.DINNER)

    // Find the next meal today
    for (mealType in mealOrder) {
        if (currentTime.isBefore(mealType.startTime)) {
            val dayName = currentDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            val menuItems = messData[mealType]?.get(dayName) ?: emptyList()
            return MealStatus(
                isNowServing = false,
                meal = Meal(mealType, menuItems),
                dayName = dayName
            )
        }
    }

    // All meals for today are over - show next day's breakfast
    val nextDate = currentDate.plusDays(1)
    val nextDayName = nextDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val breakfastItems = messData[MealType.BREAKFAST]?.get(nextDayName) ?: emptyList()

    return MealStatus(
        isNowServing = false,
        meal = Meal(MealType.BREAKFAST, breakfastItems),
        dayName = nextDayName
    )
}

// ======== MAIN ACTIVITY ========
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themePreference = remember { ThemePreference(applicationContext) }
            val themeMode by themePreference.themeModeFlow.collectAsState(initial = ThemeMode.LIGHT)
            val scope = rememberCoroutineScope()

            MessMenuTheme(themeMode = themeMode) {
                MessMenuApp(
                    themeMode = themeMode,
                    onThemeToggle = {
                        val next = themePreference.toggled(themeMode)
                        scope.launch { themePreference.setThemeMode(next) }
                    }
                )
            }
        }
    }
}

// ======== COMPOSABLE UI ========
/**
 * Main app composable that handles state, auto-refresh, and theme toggle.
 *
 * @param themeMode Current theme mode (LIGHT or DARK)
 * @param onThemeToggle Callback when user toggles theme
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessMenuApp(
    themeMode: ThemeMode = ThemeMode.LIGHT,
    onThemeToggle: () -> Unit = {}
) {
    val context = LocalContext.current

    // Navigation state
    var showFullMenu by remember { mutableStateOf(false) }

    // Load mess data off the main thread to improve time-to-first-frame.
    var messData by remember { mutableStateOf<Map<MealType, Map<String, List<String>>>>(emptyMap()) }
    var dataLoadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { loadMessData(context) }
        if (loaded.isEmpty()) {
            dataLoadFailed = true
        }
        messData = loaded
    }

    // State for current time - triggers recomposition
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    var currentDate by remember { mutableStateOf(LocalDate.now()) }

    // Auto-refresh every 60 seconds
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000) // 60 seconds
            currentTime = LocalTime.now()
            currentDate = LocalDate.now()
        }
    }

    // Get current meal status
    val mealStatus = getMealStatus(messData, currentTime, currentDate)

    // Handle back button press
    BackHandler(enabled = showFullMenu) {
        showFullMenu = false
    }

    if (showFullMenu) {
        // Full Menu Screen
        FullMenuScreen(
            messData = messData,
            themeMode = themeMode,
            onThemeToggle = onThemeToggle,
            onBackClick = { showFullMenu = false }
        )
    } else {
        // Home Screen
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Mess Menu",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    actions = {
                        ThemeToggleButton(
                            themeMode = themeMode,
                            onToggle = onThemeToggle
                        )
                    }
                )
            }
        ) { innerPadding ->
            when {
                messData.isEmpty() && !dataLoadFailed -> {
                    LoadingState(modifier = Modifier.padding(innerPadding))
                }
                mealStatus != null -> {
                    MessMenuContent(
                        mealStatus = mealStatus,
                        modifier = Modifier.padding(innerPadding),
                        onViewFullMenuClick = { showFullMenu = true }
                    )
                }
                else -> {
                    // Fallback if data loading fails
                    ErrorState(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Minimal theme toggle button.
 *
 * IMPORTANT: icon indicates the *action*:
 * - ☀️ means "switch to Light"
 * - 🌙 means "switch to Dark"
 */
@Composable
fun ThemeToggleButton(
    themeMode: ThemeMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Icon indicates the action:
    // ☀️ switches to Light, 🌙 switches to Dark
    val (icon, desc) = when (themeMode) {
        ThemeMode.DARK -> Icons.Filled.LightMode to "Switch to Light mode"
        ThemeMode.LIGHT -> Icons.Filled.DarkMode to "Switch to Dark mode"
    }

    IconButton(
        onClick = onToggle,
        modifier = modifier.size(48.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Main content layout with header, meal info, and menu items.
 */
@Composable
fun MessMenuContent(
    mealStatus: MealStatus,
    modifier: Modifier = Modifier,
    onViewFullMenuClick: () -> Unit = {}
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top spacing for edge-to-edge
        item { Spacer(modifier = Modifier.height(8.dp)) }

        // Status Header (Now Serving / Upcoming Dish)
        item {
            StatusHeader(isNowServing = mealStatus.isNowServing)
        }

        // Meal Info Card
        item {
            MealInfoCard(
                mealType = mealStatus.meal.type,
                dayName = mealStatus.dayName
            )
        }

        // Menu Section Header
        item {
            Text(
                text = "Menu",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // Menu Items
        itemsIndexed(mealStatus.meal.menuItems) { index, item ->
            MenuItemRow(
                index = index + 1,
                itemName = item
            )
        }

        // View Full Menu Button
        item {
            Button(
                onClick = onViewFullMenuClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Text(
                    text = "View Full Menu",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }

        // Bottom spacing
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

/**
 * Header showing "Now Serving" or "Upcoming Dish" with visual indicator.
 */
@Composable
fun StatusHeader(isNowServing: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status indicator dot
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(
                    if (isNowServing) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
        )

        Text(
            text = if (isNowServing) "Now Serving" else "Upcoming Dish",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

/**
 * Card displaying meal name, timing, and day.
 */
@Composable
fun MealInfoCard(
    mealType: MealType,
    dayName: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Meal name (large, prominent)
            Text(
                text = mealType.displayName,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // Timing
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = mealType.getTimingString(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )

                Text(
                    text = dayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Single menu item row with index number.
 */
@Composable
fun MenuItemRow(
    index: Int,
    itemName: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Index number with subtle background
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // Menu item name
            Text(
                text = itemName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Error state shown when data loading fails.
 */
@Composable
fun ErrorState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Unable to load menu",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Please check the app data",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Full Menu Screen with its own Scaffold and TopAppBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullMenuScreen(
    messData: Map<MealType, Map<String, List<String>>>,
    themeMode: ThemeMode,
    onThemeToggle: () -> Unit,
    onBackClick: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Weekly Menu",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Go back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                actions = {
                    ThemeToggleButton(
                        themeMode = themeMode,
                        onToggle = onThemeToggle
                    )
                }
            )
        }
    ) { innerPadding ->
        FullMenuContent(
            messData = messData,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

/**
 * Full menu content showing all meals for all days of the week.
 */
@Composable
fun FullMenuContent(
    messData: Map<MealType, Map<String, List<String>>>,
    modifier: Modifier = Modifier
) {
    val days = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top spacing
        Spacer(modifier = Modifier.height(8.dp))

        // Current day indicator
        val today = LocalDate.now().dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Text(
                    text = "Today is $today",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // Display menu for each day
        days.forEach { day ->
            val isToday = day == today
            DayMenuSection(
                dayName = day,
                messData = messData,
                isToday = isToday
            )
        }

        // Bottom spacing for navigation bar
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * Section showing all meals for a specific day.
 */
@Composable
fun DayMenuSection(
    dayName: String,
    messData: Map<MealType, Map<String, List<String>>>,
    isToday: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isToday)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isToday) 4.dp else 0.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Day name header with indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Day indicator
                if (isToday) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }

                Text(
                    text = dayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isToday)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isToday) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "Today",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Each meal type in a grid-like layout
            MealType.entries.forEach { mealType ->
                val items = messData[mealType]?.get(dayName) ?: emptyList()
                if (items.isNotEmpty()) {
                    MealSection(
                        mealType = mealType,
                        items = items
                    )
                }
            }
        }
    }
}

/**
 * Section showing a specific meal type and its items.
 */
@Composable
fun MealSection(
    mealType: MealType,
    items: List<String>
) {
    val mealColor = when (mealType) {
        MealType.BREAKFAST -> MaterialTheme.colorScheme.tertiary
        MealType.LUNCH -> MaterialTheme.colorScheme.primary
        MealType.HIGH_TEA -> MaterialTheme.colorScheme.secondary
        MealType.DINNER -> MaterialTheme.colorScheme.error
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Meal type header card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Meal type header with timing
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Meal type color indicator
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(mealColor)
                        )

                        Text(
                            text = mealType.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = mealColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = mealType.getTimingString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = mealColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    thickness = 1.dp
                )

                // Menu items as a list with better visibility
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items.forEachIndexed { index, item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Small index number
                            Text(
                                text = "${index + 1}.",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = mealColor
                            )

                            // Menu item name
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

// ======== PREVIEWS ========
@Preview(showBackground = true, showSystemUi = true)
@Composable
fun MessMenuPreview() {
    MessMenuTheme(themeMode = ThemeMode.LIGHT) {
        // Sample data for preview
        val sampleMealStatus = MealStatus(
            isNowServing = true,
            meal = Meal(
                type = MealType.LUNCH,
                menuItems = listOf(
                    "Roti",
                    "Kadhai Veg / Besan Gatte ki Sabji",
                    "Dal Tadka",
                    "Plain Rice",
                    "Fryums",
                    "Pineapple Halwa",
                    "Pickle"
                )
            ),
            dayName = "Monday"
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            MessMenuContent(
                mealStatus = sampleMealStatus,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun MessMenuUpcomingPreview() {
    MessMenuTheme(themeMode = ThemeMode.LIGHT) {
        val sampleMealStatus = MealStatus(
            isNowServing = false,
            meal = Meal(
                type = MealType.DINNER,
                menuItems = listOf(
                    "Roti",
                    "Veg Kolhapuri",
                    "Dal Fry",
                    "Plain Rice",
                    "South Indian Plain Rice",
                    "Tomato Rasam",
                    "Pickle"
                )
            ),
            dayName = "Monday"
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            MessMenuContent(
                mealStatus = sampleMealStatus,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun MessMenuDarkPreview() {
    MessMenuTheme(themeMode = ThemeMode.DARK) {
        val sampleMealStatus = MealStatus(
            isNowServing = true,
            meal = Meal(
                type = MealType.BREAKFAST,
                menuItems = listOf(
                    "Idli Vada",
                    "Sambar",
                    "Chutney",
                    "Banana",
                    "Bread",
                    "Butter Jam",
                    "Tea/Milk/Coffee"
                )
            ),
            dayName = "Tuesday"
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            MessMenuContent(
                mealStatus = sampleMealStatus,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}


@Preview(showBackground = true, showSystemUi = true)
@Composable
fun ErrorStatePreview() {
    MessMenuTheme(themeMode = ThemeMode.LIGHT) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            ErrorState(modifier = Modifier.padding(innerPadding))
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun FullMenuPreview() {
    MessMenuTheme(themeMode = ThemeMode.LIGHT) {
        // Sample data for preview
        val sampleMessData = mapOf(
            MealType.BREAKFAST to mapOf(
                "Monday" to listOf("Idli Vada", "Sambar", "Chutney", "Banana", "Bread", "Butter Jam", "Tea/Milk/Coffee"),
                "Tuesday" to listOf("Poha Jalebi", "Chutney", "Jeera Aloo", "Mix Cut Fruit Salad", "Bread", "Butter Jam", "Tea/Milk/Coffee"),
                "Wednesday" to listOf("Besan Chilla / Missal Pav", "Chutney", "Sprouts", "Banana", "Boiled Egg", "Bread", "Butter Jam", "Tea/Milk/Coffee"),
                "Thursday" to listOf("Vermicelli / Paratha", "Aloo Bhaji", "Papaya", "Bread", "Butter Jam", "Tea/Milk/Coffee"),
                "Friday" to listOf("Uttapam", "Onion Tomato Chutney", "Sprouts", "Banana", "Boiled Egg", "Bread Butter Jam", "Tea/Milk/Coffee"),
                "Saturday" to listOf("Chole Bhature", "Mix Cut Fruit Salad", "Bread", "Butter Jam", "Tea/Milk/Coffee"),
                "Sunday" to listOf("Masala Dosa / Idli", "Sambar Chutney", "Sprouts", "Banana", "Bread Butter Jam", "Tea/Milk/Coffee")
            ),
            MealType.LUNCH to mapOf(
                "Monday" to listOf("Roti", "Kadhai Veg", "Dal Tadka", "Plain Rice", "Fryums", "Pineapple Halwa", "Pickle"),
                "Tuesday" to listOf("Puri", "Chole", "Mix Dal", "Plain Rice", "Mix Salad", "South Indian Plain Rice", "Pickle"),
                "Wednesday" to listOf("Roti", "Veg Kofta", "Dal Tadka", "Mutter Pulav", "Fryums", "Sweet Boondi", "Pickle"),
                "Thursday" to listOf("Mix Pulse Tadka Masala", "Plain Dal", "Plain Rice", "Mix Salad", "Lemon Rice", "Beetroot Poriyal", "Pickle"),
                "Friday" to listOf("Roti", "Kadi Pakoda", "Dal Fry", "Plain Rice", "Fryums", "Brinjal Kuzhambu", "Pickle"),
                "Saturday" to listOf("Roti", "Aloo Jeera Dry", "Mix Dal", "Jeera Rice", "Mix Salad", "Potato Poriyal", "Pickle"),
                "Sunday" to listOf("Roti", "Veg Biryani", "Butter Paneer Masala", "Dal Khichdi", "Onion Raita", "Pickle")
            ),
            MealType.HIGH_TEA to mapOf(
                "Monday" to listOf("Vada Pav", "Sauce/Chutney", "Tea/Coffee/Milk"),
                "Tuesday" to listOf("Variety of Samosa", "Tea/Coffee/Milk"),
                "Wednesday" to listOf("Aloo Channa Chat", "Green Chutney", "Tea/Coffee/Milk"),
                "Thursday" to listOf("Aloo Vada/Fried Idli", "Tamarind Chutney", "Tea/Coffee/Milk"),
                "Friday" to listOf("Veg Chowmein", "Tea/Coffee/Milk"),
                "Saturday" to listOf("Dhokla", "Green Chutney", "Tea/Coffee/Milk"),
                "Sunday" to listOf("White Sauce Pasta", "Sauce/Chutney", "Tea/Coffee/Milk")
            ),
            MealType.DINNER to mapOf(
                "Monday" to listOf("Roti", "Veg Kolhapuri", "Dal Fry", "Plain Rice", "South Indian Plain Rice", "Tomato Rasam", "Pickle"),
                "Tuesday" to listOf("Roti", "Aloo Mutter Jhol", "Dal Tadka", "Plain Rice", "Pepper Rasam", "Hot & Sour Soup", "Pickle"),
                "Wednesday" to listOf("Roti", "Paneer Masala", "Plain Dal", "Plain Rice", "Inji Rasam", "Pickle"),
                "Thursday" to listOf("Roti", "Egg Gravy", "Ghee Rice", "Dal Fry", "Jeera Rice", "Veg Manchow Soup", "Pickle"),
                "Friday" to listOf("Roti", "Butter Chicken", "Kadai Paneer", "Dal Tadka", "Rice", "Puli Rasam", "Pickle"),
                "Saturday" to listOf("Roti", "Gobi Mutter Jhol", "Jeera Rice", "Dal Fry", "Pepper Rasam", "Hot & Sour Soup", "Pickle"),
                "Sunday" to listOf("Roti", "Aloo Soya Bean Masala", "Dal Makhani", "Plain Rice", "Paruppu Rasam", "Gulab Jamun")
            )
        )

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            FullMenuContent(messData = sampleMessData)
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun FullMenuDarkPreview() {
    MessMenuTheme(themeMode = ThemeMode.DARK) {
        val sampleMessData = mapOf(
            MealType.BREAKFAST to mapOf(
                "Monday" to listOf("Idli Vada", "Sambar", "Chutney", "Banana", "Bread", "Butter Jam", "Tea/Milk/Coffee"),
                "Tuesday" to listOf("Poha Jalebi", "Chutney", "Jeera Aloo", "Mix Cut Fruit Salad", "Bread", "Butter Jam", "Tea/Milk/Coffee")
            ),
            MealType.LUNCH to mapOf(
                "Monday" to listOf("Roti", "Kadhai Veg", "Dal Tadka", "Plain Rice", "Fryums", "Pineapple Halwa", "Pickle"),
                "Tuesday" to listOf("Puri", "Chole", "Mix Dal", "Plain Rice", "Mix Salad", "South Indian Plain Rice", "Pickle")
            ),
            MealType.HIGH_TEA to mapOf(
                "Monday" to listOf("Vada Pav", "Sauce/Chutney", "Tea/Coffee/Milk"),
                "Tuesday" to listOf("Variety of Samosa", "Tea/Coffee/Milk")
            ),
            MealType.DINNER to mapOf(
                "Monday" to listOf("Roti", "Veg Kolhapuri", "Dal Fry", "Plain Rice", "South Indian Plain Rice", "Tomato Rasam", "Pickle"),
                "Tuesday" to listOf("Roti", "Aloo Mutter Jhol", "Dal Tadka", "Plain Rice", "Pepper Rasam", "Hot & Sour Soup", "Pickle")
            )
        )

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            FullMenuContent(messData = sampleMessData)
        }
    }
}

