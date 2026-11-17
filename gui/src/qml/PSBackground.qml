import QtQuick
import QtQuick.Shapes

// Deep-blue backdrop in the spirit of the PlayStation Remote Play app:
// a vertical navy gradient, two soft glows and flowing light ribbons.
// It plays one short intro drift and then stays still: chiaki-ng composites the
// QML layer itself, so an idle menu must not re-render every frame.
Item {
    id: bg
    property bool animated: visible
    property real phase: 0
    // Dim the whole backdrop (used behind dialogs and overlays)
    property real intensity: 1.0
    clip: true

    NumberAnimation on phase {
        from: 0
        to: 1.2
        duration: 1800
        easing.type: Easing.OutCubic
        running: bg.animated
    }

    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0.0; color: "#0d2a63" }
            GradientStop { position: 0.45; color: "#081a40" }
            GradientStop { position: 1.0; color: "#030a1c" }
        }
    }

    // Large soft glows
    Repeater {
        model: [
            { cx: 0.18, cy: 0.22, r: 0.55, c: "#2a6cff", a: 0.30, s: 1.0 },
            { cx: 0.85, cy: 0.80, r: 0.60, c: "#00a0ff", a: 0.18, s: -0.7 }
        ]
        delegate: Shape {
            required property var modelData
            readonly property real radius: Math.max(bg.width, bg.height) * modelData.r
            width: radius * 2
            height: radius * 2
            x: bg.width * modelData.cx - radius + Math.sin(bg.phase * modelData.s) * bg.width * 0.04
            y: bg.height * modelData.cy - radius + Math.cos(bg.phase * modelData.s) * bg.height * 0.05
            opacity: modelData.a * bg.intensity
            ShapePath {
                strokeWidth: -1
                fillGradient: RadialGradient {
                    centerX: width / 2; centerY: height / 2
                    centerRadius: width / 2
                    focalX: centerX; focalY: centerY
                    GradientStop { position: 0.0; color: modelData.c }
                    GradientStop { position: 1.0; color: "transparent" }
                }
                startX: 0; startY: 0
                PathLine { x: width; y: 0 }
                PathLine { x: width; y: height }
                PathLine { x: 0; y: height }
                PathLine { x: 0; y: 0 }
            }
        }
    }

    // Flowing light ribbons
    Repeater {
        model: [
            { y0: 0.62, amp: 0.10, off: 0.0, w: 2.0, a: 0.22 },
            { y0: 0.68, amp: 0.08, off: 1.3, w: 1.2, a: 0.16 },
            { y0: 0.74, amp: 0.12, off: 2.4, w: 1.0, a: 0.12 },
            { y0: 0.58, amp: 0.06, off: 3.7, w: 0.8, a: 0.10 }
        ]
        delegate: Shape {
            required property var modelData
            anchors.fill: parent
            opacity: modelData.a * bg.intensity
            readonly property real p: bg.phase + modelData.off
            ShapePath {
                strokeColor: "#bfe0ff"
                strokeWidth: modelData.w
                fillColor: "transparent"
                capStyle: ShapePath.RoundCap
                startX: -20
                startY: bg.height * (modelData.y0 + Math.sin(p) * modelData.amp * 0.4)
                PathCubic {
                    x: bg.width + 20
                    y: bg.height * (modelData.y0 - 0.12 + Math.cos(p * 0.8) * modelData.amp * 0.5)
                    control1X: bg.width * 0.33
                    control1Y: bg.height * (modelData.y0 - Math.sin(p) * modelData.amp)
                    control2X: bg.width * 0.66
                    control2Y: bg.height * (modelData.y0 + Math.cos(p * 1.2) * modelData.amp)
                }
            }
        }
    }

    // Subtle vignette
    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0.0; color: "#00000000" }
            GradientStop { position: 0.75; color: "#00000000" }
            GradientStop { position: 1.0; color: "#66000000" }
        }
    }
}
