package com.rohittp.reng.internal.identity

import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.AnimationSelector
import com.rohittp.reng.AnimationTrack
import com.rohittp.reng.Camera
import com.rohittp.reng.FramePlan
import com.rohittp.reng.Geometry
import com.rohittp.reng.Model
import com.rohittp.reng.Placement
import com.rohittp.reng.ProjectionMode
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.Sticker
import com.rohittp.reng.Vector3

internal fun canonicalV1MinimalFramePlan(): FramePlan = FramePlan(
    frameIndex = 0,
    camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
)

internal fun canonicalV1RepresentativeFramePlan(): FramePlan = FramePlan(
    frameIndex = 42,
    camera = Camera(12.5, -179.25, 8.5, 45.0, 25.0),
    projectionMode = ProjectionMode.MERCATOR,
    drawBasemap = true,
    stickers = listOf(
        Sticker(
            Placement(
                AnchoringMode.MAP,
                Vector3(12.0, -179.0, 15.0),
                AnchoringMode.SCREEN,
                Vector3(0.0, 15.0, -0.0),
                AnchoringMode.SCREEN,
                2.0,
            ),
            ResourceLocator("stickers/café.png"),
        ),
        Sticker(
            Placement(
                AnchoringMode.SCREEN,
                Vector3(640.0, 360.0, 4.0),
                AnchoringMode.SCREEN,
                Vector3(0.0, 0.0, 0.0),
                AnchoringMode.SCREEN,
                1.0,
            ),
            ResourceLocator("https://example.test/sticker%20two.png"),
        ),
    ),
    models = listOf(
        Model(
            Placement(
                AnchoringMode.MAP,
                Vector3(11.0, -178.0, 20.0),
                AnchoringMode.MAP,
                Vector3(10.0, 20.0, 30.0),
                AnchoringMode.MAP,
                3.0,
            ),
            ResourceLocator("models/robot.glb"),
            ResourceLocator("textures/robot-base.png"),
            listOf(
                AnimationTrack(AnimationSelector.Name("idle"), 1.5),
                AnimationTrack(AnimationSelector.Index(7), 2.0),
            ),
        ),
        Model(
            Placement(
                AnchoringMode.SCREEN,
                Vector3(200.0, 150.0, 5.0),
                AnchoringMode.SCREEN,
                Vector3(0.0, 0.0, 0.0),
                AnchoringMode.SCREEN,
                0.5,
            ),
            ResourceLocator("models/screen.glb"),
            null,
            emptyList(),
        ),
    ),
    geometries = listOf(
        Geometry(
            Vector3(20.0, -180.0, 10.0),
            Vector3(10.0, -170.0, 20.0),
            ShaderPair(
                "#version 300 es\nin vec3 position;\nvoid main(){gl_Position=vec4(position,1.0);}",
                "#version 300 es\nprecision highp float;\nout vec4 color;\nvoid main(){color=vec4(1.0);}",
            ),
        ),
        Geometry(
            Vector3(40.0, -160.0, 0.0),
            Vector3(30.0, -150.0, 0.0),
            ShaderPair("#version 300 es\nvoid main(){}", "#version 300 es\nvoid main(){}"),
        ),
    ),
)

// ADR 0081: each geometry names its program by stable id (tag 9) instead of carrying its shader sources
// (tag 3, retired). Re-derived outside RenG with Python's hashlib by a script that first reproduced the
// previous 1,554-byte fixture's digest and the pinned ("v", "f") geometry-program digest.
internal const val CANONICAL_V1_REPRESENTATIVE_HEX: String =
    "524e47430101000100000008000000000000002a0002000000460001000000084029000000000000000200000008c066" +
    "680000000000000300000008402100000000000000040000000840468000000000000005000000084039000000000000" +
    "00030000000200010004000000010100050000017800000002000000ac00010000008e00010000000200010002000000" +
    "2a0001000000084028000000000000000200000008c066600000000000000300000008402e0000000000000003000000" +
    "02000200040000002a0001000000080000000000000000000200000008402e0000000000000003000000080000000000" +
    "000000000500000002000200060000000840000000000000000007000000020001000200000012737469636b6572732f" +
    "636166c3a92e706e67000000c000010000008e000100000002000200020000002a000100000008408400000000000000" +
    "020000000840768000000000000003000000084010000000000000000300000002000200040000002a00010000000800" +
    "000000000000000002000000080000000000000000000300000008000000000000000000050000000200020006000000" +
    "083ff0000000000000000700000002000100020000002668747470733a2f2f6578616d706c652e746573742f73746963" +
    "6b657225323074776f2e706e670006000001f2000000020000012a00010000008e000100000002000100020000002a00" +
    "01000000084026000000000000000200000008c066400000000000000300000008403400000000000000030000000200" +
    "0100040000002a00010000000840240000000000000002000000084034000000000000000300000008403e0000000000" +
    "000005000000020001000600000008400800000000000000070000000200010002000000106d6f64656c732f726f626f" +
    "742e676c620003000000180174657874757265732f726f626f742d626173652e706e6700040000005c00000002000000" +
    "26000100000012000100000002000200020000000469646c650002000000083ff80000000000000000002a0001000000" +
    "16000100000002000100020000000800000000000000070002000000084000000000000000000000bc00010000008e00" +
    "0100000002000200020000002a00010000000840690000000000000002000000084062c0000000000000030000000840" +
    "14000000000000000300000002000200040000002a000100000008000000000000000000020000000800000000000000" +
    "00000300000008000000000000000000050000000200020006000000083fe00000000000000007000000020001000200" +
    "0000116d6f64656c732f73637265656e2e676c62000300000001000004000000040000000000070000019e0000000200" +
    "0000c900010000002a0001000000084034000000000000000200000008c0668000000000000003000000084024000000" +
    "00000000020000002a0001000000084024000000000000000200000008c0654000000000000003000000084034000000" +
    "000000000400000004000000000005000000040000000000060000000200010007000000010000090000004064636661" +
    "363364336161623935386636346462633237633534643566643065323336616234346236623036663264646666633264" +
    "393062306663323761643230000000c900010000002a0001000000084044000000000000000200000008c06400000000" +
    "0000000300000008000000000000000000020000002a000100000008403e000000000000000200000008c062c0000000" +
    "000000030000000800000000000000000004000000040000000000050000000400000000000600000002000100070000" +
    "000100000900000040363161626138316537353963383063623938363534653733336239663066336237343835313262" +
    "323961663464336665626265613639653765396639656434610008000000010100090000000100000a0000000100"

internal fun String.canonicalFixtureHexToByteArray(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun CanonicalBytes.fixtureLowercaseHex(): String = bytes.toFixtureLowercaseHex()

internal fun ByteArray.toFixtureLowercaseHex(): String = buildString(size * 2) {
    this@toFixtureLowercaseHex.forEach { byte ->
        append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
        append(HEX_DIGITS[byte.toInt() and 0x0f])
    }
}

private const val HEX_DIGITS: String = "0123456789abcdef"
