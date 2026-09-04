package com.example.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.app.domain.VideoResult
import com.example.app.playback.PlaybackManager
import com.example.app.ui.SearchUiState
import com.example.app.ui.SearchViewModel
import com.example.app.ui.theme.AppTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { AppTheme { VideoApp() } } }
}

private enum class AppTab(val label: String) { Home("Home"), Shorts("Shorts"), MyVideos("My Videos"), Local("Local") }

@Composable
private fun VideoApp(searchViewModel: SearchViewModel = viewModel()) {
    val context = LocalContext.current
    val manager = remember { PlaybackManager(context) }
    var tab by rememberSaveable { mutableStateOf(AppTab.Home) }
    var selectedVideo by remember { mutableStateOf<VideoResult?>(null) }
    var googleAccount by remember { mutableStateOf(GoogleSignIn.getLastSignedInAccount(context)) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var showNotifications by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = { AppTopBar(searchViewModel, googleAccount, { showNotifications = true }, { showAccount = true }) },
        bottomBar = { AppBottomBar(tab) { tab = it } }
    ) { padding ->
        when (tab) {
            AppTab.Home -> HomeScreen(searchViewModel, Modifier.padding(padding)) { selectedVideo = it; tab = AppTab.MyVideos }
            AppTab.Shorts -> ShortsScreen(searchViewModel, Modifier.padding(padding)) { selectedVideo = it; tab = AppTab.MyVideos }
            AppTab.MyVideos -> MyVideosScreen(selectedVideo, manager, Modifier.padding(padding))
            AppTab.Local -> LocalScreen(manager, Modifier.padding(padding))
        }
    }
    if (showAccount) GoogleAccountDialog(
        onDismiss = { showAccount = false },
        onSignedIn = { account ->
            googleAccount = account
            tab = AppTab.Home
            searchViewModel.loadSuggestedVideos()
        },
        onSignedOut = { googleAccount = null }
    )
    if (showNotifications) NotificationsDialog { showNotifications = false }
}

@Composable
private fun AppTopBar(viewModel: SearchViewModel, account: GoogleSignInAccount?, onNotifications: () -> Unit, onAccount: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Surface(shadowElevation = 3.dp) { Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary) { Icon(Icons.Default.PlayArrow, "Video Companion", Modifier.padding(7.dp), tint = MaterialTheme.colorScheme.onPrimary) }
            Spacer(Modifier.width(10.dp)); Text("Video Companion", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onNotifications) { Icon(Icons.Default.NotificationsNone, "Notifications") }
            IconButton(onClick = onAccount) { GoogleAccountAvatar(account) }
        }
        OutlinedTextField(state.query, viewModel::onQueryChanged, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, label = { Text("Search YouTube") })
    } }
}

@Composable
private fun GoogleAccountAvatar(account: GoogleSignInAccount?) {
    val photoUrl = account?.photoUrl?.toString()
    val image = produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, photoUrl) {
        value = photoUrl?.let { url ->
            withContext(Dispatchers.IO) {
                runCatching { URL(url).openStream().use(BitmapFactory::decodeStream)?.asImageBitmap() }.getOrNull()
            }
        }
    }.value
    Surface(
        modifier = Modifier.size(32.dp).clip(CircleShape),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CircleShape
    ) {
        if (image != null) {
            Image(BitmapPainter(image), contentDescription = "YouTube account", contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Default.AccountCircle, contentDescription = "YouTube account", modifier = Modifier.padding(3.dp))
        }
    }
}

@Composable
private fun AppBottomBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    val icons = mapOf(AppTab.Home to Icons.Default.Home, AppTab.Shorts to Icons.Default.SmartDisplay, AppTab.MyVideos to Icons.Default.VideoLibrary, AppTab.Local to Icons.Default.Folder)
    NavigationBar { AppTab.entries.forEach { item -> NavigationBarItem(selected == item, { onSelect(item) }, { Icon(icons.getValue(item), item.label) }, label = { Text(item.label) }) } }
}

@Composable
private fun HomeScreen(viewModel: SearchViewModel, modifier: Modifier, onOpen: (VideoResult) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    VideoFeed("Home", "Suggested videos", state, modifier, listOf("Music", "Gaming", "Learning", "News"), viewModel::onQueryChanged, onOpen)
}

@Composable
private fun ShortsScreen(viewModel: SearchViewModel, modifier: Modifier, onOpen: (VideoResult) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (state.query != "short videos") viewModel.onQueryChanged("short videos") }
    VideoFeed("Shorts", "Quick videos from YouTube", state, modifier, emptyList(), {}, onOpen)
}

@Composable
private fun VideoFeed(title: String, subtitle: String, state: SearchUiState, modifier: Modifier, chips: List<String>, onChip: (String) -> Unit, onOpen: (VideoResult) -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(8.dp)); Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (chips.isNotEmpty()) item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips.forEach { AssistChip({ onChip(it) }, { Text(it) }) } } }
        if (state.loading) item { Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        items(state.videos, key = { it.id }) { VideoRow(it) { onOpen(it) } }
    }
}

@Composable
private fun VideoRow(video: VideoResult, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(120.dp, 70.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(4.dp)); Text(video.channel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } }
}

@Composable
private fun MyVideosScreen(video: VideoResult?, manager: PlaybackManager, modifier: Modifier) {
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("My Videos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Your selected videos and authorized playback", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (video != null) item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(video.title, fontWeight = FontWeight.Bold); Text(video.channel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${video.id}"))) } catch (_: ActivityNotFoundException) { } }) { Text("Open in YouTube") }
        } } } else item { EmptyState("No videos selected", "Choose a video from Home or Shorts to keep it here.") }
        item { AuthorizedPlayer(manager) }
    }
}

@Composable
private fun LocalScreen(manager: PlaybackManager, modifier: Modifier) {
    val context = LocalContext.current
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; selectedName = uri.lastPathSegment; manager.playAuthorizedUri(uri)
    } }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Local", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Play video or audio stored on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Private local playback", fontWeight = FontWeight.Bold); Text("Choose a file with Android's picker. The app requests access only to the chosen item.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { picker.launch(arrayOf("video/*", "audio/*")) }) { Text("Choose media file") }; selectedName?.let { Text("Now playing: $it", style = MaterialTheme.typography.bodySmall) }
        } } }
        item { AuthorizedPlayer(manager, false) }
    }
}

@Composable
private fun AuthorizedPlayer(manager: PlaybackManager, allowUrlInput: Boolean = true) {
    val playback by manager.state.collectAsStateWithLifecycle(); val timer by manager.sleepTimerSeconds.collectAsStateWithLifecycle(); var sourceUrl by rememberSaveable { mutableStateOf("") }
    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Background player", fontWeight = FontWeight.Bold)
        if (allowUrlInput) { OutlinedTextField(sourceUrl, { sourceUrl = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Authorized HTTPS media URL") }); Button(onClick = { if (sourceUrl.startsWith("https://")) manager.playAuthorizedUrl(sourceUrl) }) { Text("Play URL") } }
        if (playback.isReady) { Text("${formatTime(playback.positionMs)} / ${formatTime(playback.durationMs)}"); Slider(playback.positionMs.toFloat(), { manager.seekTo(it.toLong()) }, valueRange = 0f..playback.durationMs.coerceAtLeast(1).toFloat()); Row { Button(manager::toggle) { Text(if (playback.isPlaying) "Pause" else "Play") }; Spacer(Modifier.width(8.dp)); TextButton(manager::stop) { Text("Stop") } } }
        HorizontalDivider(); Text(if (timer > 0) "Sleep timer: ${formatTime(timer * 1_000L)}" else "Set a sleep timer", style = MaterialTheme.typography.bodySmall)
        Row { listOf(15, 30, 45).forEach { TextButton({ manager.startSleepTimer(it) }) { Text("$it min") } }; if (timer > 0) TextButton(manager::cancelSleepTimer) { Text("Cancel") } }
    } }
}

@Composable private fun EmptyState(title: String, message: String) = Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun NotificationsDialog(onDismiss: () -> Unit) = AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onDismiss) { Text("Done") } }, title = { Text("Notifications") }, text = { Text("There are no new notifications. Notification preferences will be connected to your account when you sign in.") })

@Composable
private fun GoogleAccountDialog(
    onDismiss: () -> Unit,
    onSignedIn: (GoogleSignInAccount) -> Unit,
    onSignedOut: () -> Unit
) {
    val context = LocalContext.current
    val options = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/youtube.readonly"))
            .apply {
                // ID tokens are needed only when a backend verifies the user.  Leaving the
                // local setting empty still permits a profile-only Google Sign-In.
                if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) {
                    requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                }
            }
            .build()
    }
    val client = remember(options) { GoogleSignIn.getClient(context, options) }
    var account by remember { mutableStateOf(GoogleSignIn.getLastSignedInAccount(context)) }; var error by rememberSaveable { mutableStateOf<String?>(null) }
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        account = try {
            GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java).also(onSignedIn)
        } catch (e: ApiException) {
            error = googleSignInError(e.statusCode)
            null
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("YouTube account") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (account == null) Text("Sign in with the Google account that you use for YouTube.") else { Text(account?.displayName ?: "Signed in"); Text(account?.email.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant) }; error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    } }, confirmButton = { if (account == null) Button({ error = null; signIn.launch(client.signInIntent) }) { Text("Sign in to YouTube") } else Button({ client.signOut().addOnCompleteListener { account = null; onSignedOut() } }) { Text("Sign out") } }, dismissButton = { TextButton(onDismiss) { Text("Close") } })
}

private fun googleSignInError(statusCode: Int): String = when (statusCode) {
    CommonStatusCodes.DEVELOPER_ERROR -> "Google Sign-In is not configured for this app. Register package com.example.app and its signing SHA-1 in the Android OAuth client."
    GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> "Google Sign-In was cancelled."
    CommonStatusCodes.NETWORK_ERROR -> "Google Sign-In needs a network connection."
    else -> "Google Sign-In failed (status $statusCode). Please try again."
}

private fun formatTime(milliseconds: Long): String { val seconds = (milliseconds / 1_000).coerceAtLeast(0); return "%d:%02d".format(seconds / 60, seconds % 60) }
