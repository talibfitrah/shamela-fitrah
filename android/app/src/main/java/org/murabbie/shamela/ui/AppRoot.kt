package org.murabbie.shamela.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.murabbie.shamela.App
import org.murabbie.shamela.data.LibraryState
import org.murabbie.shamela.ui.screens.AboutScreen
import org.murabbie.shamela.ui.screens.AuthorBooksScreen
import org.murabbie.shamela.ui.screens.BookScreen
import org.murabbie.shamela.ui.screens.CategoryBooksScreen
import org.murabbie.shamela.ui.screens.DownloadsScreen
import org.murabbie.shamela.ui.screens.LibraryScreen
import org.murabbie.shamela.ui.screens.MoreScreen
import org.murabbie.shamela.ui.screens.ReaderScreen
import org.murabbie.shamela.ui.screens.SearchScreen
import org.murabbie.shamela.ui.screens.SettingsScreen
import org.murabbie.shamela.ui.screens.SetupScreen
import org.murabbie.shamela.ui.screens.ShelfScreen
import org.murabbie.shamela.ui.screens.UpdatesScreen
import org.murabbie.shamela.ui.screens.NewBooksScreen
import org.murabbie.shamela.ui.screens.ScholarScreen
import org.murabbie.shamela.ui.screens.ServicesScreen
import org.murabbie.shamela.ui.screens.ImportScreen
import org.murabbie.shamela.ui.screens.QuranScreen
import org.murabbie.shamela.ui.screens.RowatSearchScreen
import org.murabbie.shamela.ui.screens.TakhrijInfoScreen
import org.murabbie.shamela.ui.screens.RawiScreen

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("library", "المكتبة", Icons.Default.LibraryBooks),
    Tab("search", "البحث", Icons.Default.Search),
    Tab("shelf", "قراءاتي", Icons.Default.Bookmarks),
    Tab("downloads", "التنزيلات", Icons.Default.Download),
    Tab("more", "المزيد", Icons.Default.MoreHoriz),
)

fun NavHostController.go(route: String) {
    navigate(route) { launchSingleTop = true }
}

@Composable
fun AppRoot(app: App, pendingOpen: MutableState<String?>) {
    val nav = rememberNavController()
    val libState by app.library.state.collectAsState()

    LaunchedEffect(pendingOpen.value, libState) {
        val r = pendingOpen.value ?: return@LaunchedEffect
        if (libState is LibraryState.Ready) { pendingOpen.value = null; nav.go(r) }
    }

    if (libState !is LibraryState.Ready) {
        SetupScreen(app)
        return
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: "library"
    val showBar = tabs.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBar) NavigationBar {
                for (t in tabs) NavigationBarItem(
                    selected = currentRoute == t.route,
                    onClick = {
                        nav.navigate(t.route) {
                            popUpTo("library") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(t.icon, contentDescription = t.label) },
                    label = { Text(t.label) },
                )
            }
        }
    ) { padding ->
        NavHost(nav, startDestination = "library", modifier = Modifier.padding(padding)) {
            composable("library") { LibraryScreen(app, nav) }
            composable("search") { SearchScreen(app, nav, null, null) }
            composable("search/{book}?q={q}", arguments = listOf(navArgument("book") { type = NavType.IntType }, navArgument("q") { defaultValue = "" })) {
                SearchScreen(app, nav, it.arguments?.getInt("book"), it.arguments?.getString("q"))
            }
            composable("shelf") { ShelfScreen(app, nav) }
            composable("downloads") { DownloadsScreen(app, nav) }
            composable("more") { MoreScreen(app, nav) }
            composable("settings") { SettingsScreen(app, nav) }
            composable("updates") { UpdatesScreen(app, nav) }
            composable("newbooks") { NewBooksScreen(app, nav) }
            composable("services") { ServicesScreen(app, nav) }
            composable("import") { ImportScreen(app, nav) }
            composable("quran") { QuranScreen(app, nav) }
            composable("rowat") { RowatSearchScreen(app, nav) }
            composable("takhrij") { TakhrijInfoScreen(app, nav) }
            composable("rawi/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { RawiScreen(app, nav, it.arguments!!.getInt("id")) }
            composable("scholar/{key}") { ScholarScreen(app, nav, it.arguments?.getString("key") ?: "") }
            composable("about") { AboutScreen(app, nav) }
            composable("category/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { CategoryBooksScreen(app, nav, it.arguments!!.getInt("id")) }
            composable("author/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { AuthorBooksScreen(app, nav, it.arguments!!.getInt("id")) }
            composable("book/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { BookScreen(app, nav, it.arguments!!.getInt("id")) }
            composable("reader/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { ReaderScreen(app, nav, it.arguments!!.getInt("id"), null, null) }
            composable("reader/{id}/{page}?q={q}", arguments = listOf(navArgument("id") { type = NavType.IntType }, navArgument("page") { type = NavType.IntType }, navArgument("q") { defaultValue = "" })) {
                ReaderScreen(app, nav, it.arguments!!.getInt("id"), it.arguments!!.getInt("page"), it.arguments?.getString("q")?.takeIf { s -> s.isNotBlank() })
            }
        }
    }
}
