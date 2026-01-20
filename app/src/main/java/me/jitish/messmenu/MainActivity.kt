package me.jitish.messmenu

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.jitish.messmenu.ui.theme.MessMenuTheme
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// ============================================================================
// DATA CLASSES
// ============================================================================

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

// ============================================================================
// JSON PARSING
// ============================================================================

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

// ============================================================================
// MEAL DETECTION LOGIC
// ============================================================================

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
): MealStatus? {
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

// ============================================================================
// MAIN ACTIVITY
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MessMenuTheme {
                MessMenuApp()
            }
        }
    }
}

// ============================================================================
// COMPOSABLE UI
// ============================================================================

/**
 * Main app composable that handles state and auto-refresh.
 */
@Composable
fun MessMenuApp() {
    val context = LocalContext.current

    // Load mess data once
    val messData = remember { loadMessData(context) }

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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        if (mealStatus != null) {
            MessMenuContent(
                mealStatus = mealStatus,
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            // Fallback if data loading fails
            ErrorState(modifier = Modifier.padding(innerPadding))
        }
    }
}

/**
 * Main content layout with header, meal info, and menu items.
 */
@Composable
fun MessMenuContent(
    mealStatus: MealStatus,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top spacing for edge-to-edge
        item { Spacer(modifier = Modifier.height(24.dp)) }

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

        // Bottom spacing
        item { Spacer(modifier = Modifier.height(32.dp)) }
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

// ============================================================================
// PREVIEWS
// ============================================================================

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun MessMenuPreview() {
    MessMenuTheme {
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
    MessMenuTheme {
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
    MessMenuTheme(darkTheme = true) {
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