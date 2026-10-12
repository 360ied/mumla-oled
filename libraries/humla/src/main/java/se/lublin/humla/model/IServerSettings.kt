package se.lublin.humla.model

interface IServerSettings {
    val allowHtml: Boolean

    val messageLength: Int

    val imageMessageLength: Int

    val maxBandwidth: Int

    val maxUsers: Int

    val welcomeText: String
}
