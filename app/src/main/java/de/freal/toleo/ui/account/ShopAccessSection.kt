package de.freal.toleo.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.freal.toleo.AppContainer
import de.freal.toleo.tolino.ShopLogin
import de.freal.toleo.tolino.ShopProxy
import kotlinx.coroutines.launch

/**
 * Proxy for reaching the shop from outside Germany/Austria/Switzerland, and the stored login for
 * automatic re-login. Edits are kept in local state and saved with the Save buttons.
 */
@Composable
fun ShopAccessSection(container: AppContainer, modifier: Modifier = Modifier) {
    val settings by container.shopAccess.settings.collectAsState()
    var open by remember { mutableStateOf(settings.proxy.enabled || settings.login.enabled) }
    Column(modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Access from abroad", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
        }
        Text(
            "Thalia blocks sign-in from outside Germany, Austria and Switzerland. A German proxy is used only for the " +
                "shop's sign-in pages; books and sync connect directly.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (open) {
            ProxyCard(container, settings.proxy)
            LoginCard(container, settings.login)
        }
    }
}

@Composable
private fun ProxyCard(container: AppContainer, saved: ShopProxy) {
    var p by remember(saved) { mutableStateOf(saved) }
    var portText by remember(saved) { mutableStateOf(if (saved.port > 0) saved.port.toString() else "") }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Proxy for the shop", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = p.enabled, onCheckedChange = { p = p.copy(enabled = it) })
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("http" to "HTTP proxy", "https" to "HTTPS proxy").forEachIndexed { i, (scheme, label) ->
                    SegmentedButton(
                        selected = p.scheme == scheme,
                        onClick = { p = p.copy(scheme = scheme) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }
            Row {
                OutlinedTextField(
                    value = p.host, onValueChange = { p = p.copy(host = it.trim()) },
                    label = { Text("Host") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = portText, onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") }, singleLine = true, modifier = Modifier.width(110.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            OutlinedTextField(
                value = p.username, onValueChange = { p = p.copy(username = it) },
                label = { Text("Username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = p.password, onValueChange = { p = p.copy(password = it) },
                label = { Text("Password (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Text(
                "HTTP or HTTPS proxies in Germany work (many VPN providers offer them). SOCKS proxies with a password don't.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    container.shopAccess.update { it.copy(proxy = p.copy(port = portText.toIntOrNull() ?: 0)) }
                    status = "Saved"
                }) { Text("Save") }
                OutlinedButton(
                    enabled = !testing,
                    onClick = {
                        container.shopAccess.update { it.copy(proxy = p.copy(port = portText.toIntOrNull() ?: 0)) }
                        testing = true
                        status = "Testing…"
                        scope.launch {
                            val url = container.partners.partners.first().authUrl.let { android.net.Uri.parse(it) }
                                .let { "${it.scheme}://${it.host}/" }
                            val code = container.browser.probe(url)
                            status = when (code) {
                                in 200..399 -> "Thalia is reachable (HTTP $code)"
                                403 -> "Still blocked (HTTP 403). Is the proxy in Germany, Austria or Switzerland?"
                                407 -> "The proxy wants a different username or password (HTTP 407)"
                                -1 -> "Couldn't connect. Check host and port."
                                else -> "Thalia answered HTTP $code"
                            }
                            testing = false
                        }
                    },
                ) { Text("Save & test") }
            }
        }
    }
}

@Composable
private fun LoginCard(container: AppContainer, saved: ShopLogin) {
    var l by remember(saved) { mutableStateOf(saved) }
    var status by remember { mutableStateOf<String?>(null) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Automatic re-login", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = l.enabled, onCheckedChange = { l = l.copy(enabled = it) })
            }
            Text(
                "When Thalia asks for the password again, Toleo fills in its login form in the background. Stored " +
                    "encrypted on this phone and only sent to Thalia. After a failed attempt it stops until you save again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = l.email, onValueChange = { l = l.copy(email = it.trim()) },
                label = { Text("Thalia email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            OutlinedTextField(
                value = l.password, onValueChange = { l = l.copy(password = it) },
                label = { Text("Thalia password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            saved.failedAt?.let {
                Text(
                    "The last automatic login didn't get through, so it's paused. Check the details and save to try again.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    container.shopAccess.update { it.copy(login = l.copy(failedAt = null)) }
                    status = "Saved"
                }) { Text("Save") }
                OutlinedButton(onClick = {
                    container.shopAccess.update { it.copy(login = ShopLogin()) }
                    status = "Removed"
                }) { Text("Remove") }
            }
        }
    }
}
