package tr.lidertv.nativeapp.ui.live

import tr.lidertv.nativeapp.R
import tr.lidertv.nativeapp.i18n.AppLang
import tr.lidertv.nativeapp.i18n.I18n

/**
 * Libellés du déplacement manuel des chaînes (ordre personnel par profil), à part de
 * [tr.lidertv.nativeapp.i18n.DesignStrings] : ni le golden ni les porteurs existants n'ont à bouger.
 */
class ChannelOrderStrings(val lang: AppLang) {
    private fun tx(id: Int): String = I18n.string(lang, id)

    val move get() = tx(R.string.cor_move)
    val reset get() = tx(R.string.cor_reset)
    val title get() = tx(R.string.cor_title)
    val hint get() = tx(R.string.cor_hint)
    val up get() = tx(R.string.cor_up)
    val down get() = tx(R.string.cor_down)
    val done get() = tx(R.string.cor_done)
    val cancel get() = tx(R.string.cor_cancel)
}
