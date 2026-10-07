package __APP_ID__.ui.search

import __APP_ID__.ui.common.ErrorCard
import __APP_ID__.ui.common.LoadingBox
import __APP_ID__.ui.common.VideoRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun SearchScreen(viewModel: SearchViewModel, onOpenVideo: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    // Load the next page when the user is within 3 items of the end.
    LaunchedEffect(listState) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) viewModel.loadMore() }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            label = { Text("Search YouTube") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                viewModel.submit()
            }),
        )

        when {
            state.loading -> LoadingBox()
            state.error != null && state.results.isEmpty() ->
                ErrorCard(state.error!!, onRetry = viewModel::submit, modifier = Modifier.padding(16.dp))
            state.searched && state.results.isEmpty() ->
                Text("No videos found.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(state.results, key = { it.id }) { video ->
                    VideoRow(video, onClick = { onOpenVideo(video.id) })
                }
                if (state.loadingMore) item { LoadingBox() }
                state.error?.takeIf { state.results.isNotEmpty() }?.let { err ->
                    item { ErrorCard(err, onRetry = viewModel::loadMore, modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }
}
