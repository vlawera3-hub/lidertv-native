package tr.lidertv.nativeapp.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.tv.material3.Text
import tr.lidertv.nativeapp.data.xmltv.GuideAddress
import tr.lidertv.nativeapp.data.xmltv.GuideCheck
import tr.lidertv.nativeapp.data.xmltv.GuideMode
import tr.lidertv.nativeapp.i18n.LocalDs
import tr.lidertv.nativeapp.i18n.guideAddressLabel
import tr.lidertv.nativeapp.i18n.guideCheckMessage
import tr.lidertv.nativeapp.i18n.guideDialogSubtitle
import tr.lidertv.nativeapp.i18n.guideDialogTitle
import tr.lidertv.nativeapp.i18n.guideFromPhone
import tr.lidertv.nativeapp.i18n.guideSave
import tr.lidertv.nativeapp.i18n.guideVerifyAndSave
import tr.lidertv.nativeapp.i18n.guideVerifying
import tr.lidertv.nativeapp.i18n.sourceGuideMode
import tr.lidertv.nativeapp.i18n.sourceGuideModeHint
import tr.lidertv.nativeapp.i18n.sourceGuideModeLabel
import tr.lidertv.nativeapp.i18n.sourceGuideSubtitle
import tr.lidertv.nativeapp.i18n.sourceGuideTitle
import tr.lidertv.nativeapp.ui.common.design
import tr.lidertv.nativeapp.ui.design.FocusSurface
import tr.lidertv.nativeapp.ui.design.Icons
import tr.lidertv.nativeapp.ui.design.Manrope
import tr.lidertv.nativeapp.ui.design.PrefRow
import tr.lidertv.nativeapp.ui.design.Ux
import tr.lidertv.nativeapp.ui.design.spx
import kotlinx.coroutines.launch

/**
 * Saisie d'une adresse de guide XMLTV à la télécommande (même champ texte que les dialogues d'ajout de source), vérifiée
 * par une requête réseau AVANT l'enregistrement : le dialogue reste ouvert (« Vérification… ») jusqu'au verdict.
 *  - Guide complémentaire des Réglages : [perSource] = false, une adresse est exigée.
 *  - « Guide de cette source » : [perSource] = true, choix remplacer / compléter, adresse vide = retirer le guide.
 * [onPhone] : propose d'envoyer l'adresse depuis un téléphone (page /envoyer).
 */
@Composable
fun GuideAddressDialog(
    initialUrl: String,
    perSource: Boolean,
    initialMode: GuideMode = GuideMode.COMPLETE,
    onVerify: suspend (String) -> GuideCheck,
    onSave: (url: String, mode: GuideMode) -> Unit,
    onDismiss: () -> Unit,
    onPhone: (() -> Unit)? = null,
) {
    val D = LocalDs.current
    var url by remember { mutableStateOf(initialUrl) }
    var mode by remember { mutableStateOf(initialMode) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<GuideCheck?>(null) }
    val scope = rememberCoroutineScope()
    val shape = GuideAddress.validate(url)
    val shapeError = (shape as? GuideAddress.Validation.Invalid)?.takeIf { url.isNotBlank() }?.let { D.guideCheckMessage(GuideCheck.Invalid(it.problem)) }
    val removing = perSource && url.isBlank()
    val canSubmit = !busy && (removing || shape is GuideAddress.Validation.Ok)

    AddProviderDialog(
        title = if (perSource) D.sourceGuideTitle else D.guideDialogTitle,
        subtitle = if (perSource) D.sourceGuideSubtitle else D.guideDialogSubtitle,
        icon = Icons.Guide,
        submitLabel = if (removing) D.guideSave else D.guideVerifyAndSave,
        canSubmit = canSubmit,
        onDismiss = onDismiss,
        onSubmit = {
            if (removing) onSave("", mode)
            else {
                busy = true; failure = null
                scope.launch {
                    val r = onVerify(url.trim())
                    busy = false
                    if (r == GuideCheck.Ok) onSave(url.trim(), mode) else failure = r
                }
            }
        },
    ) {
        FormField(
            D.guideAddressLabel, url, { url = it; failure = null }, keyboardType = KeyboardType.Uri,
            placeholder = "https://hote.tld/guide.xml.gz", autoFocus = true, last = true, required = !perSource,
            error = shapeError, testTag = "field-guide-url",
        )
        if (perSource) PrefRow(D.sourceGuideModeLabel, D.sourceGuideMode(mode), hint = D.sourceGuideModeHint(mode)) {
            mode = if (mode == GuideMode.REPLACE) GuideMode.COMPLETE else GuideMode.REPLACE
        }
        if (busy) Text(D.guideVerifying, fontFamily = Manrope, fontSize = 22.spx, color = Ux.Text2, maxLines = 1, modifier = Modifier.testTag("guide-verifying"))
        failure?.let { f -> D.guideCheckMessage(f)?.let { Text(it, fontFamily = Manrope, fontSize = 22.spx, color = Ux.Err, maxLines = 3, modifier = Modifier.testTag("guide-error")) } }
        if (onPhone != null) FocusSurface(onClick = onPhone, shape = RoundedCornerShape(36.design), bg = Ux.Surface2, modifier = Modifier.height(72.design)) { f ->
            Box(Modifier.height(72.design).padding(horizontal = 36.design), contentAlignment = Alignment.Center) {
                Text(D.guideFromPhone, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 24.spx, color = if (f) Ux.TextOnLight else Ux.Text)
            }
        }
    }
}
