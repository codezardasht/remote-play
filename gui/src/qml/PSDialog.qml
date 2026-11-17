import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material

// Modal popup in the PlayStation style: rounded navy card that springs in,
// over a dimmed backdrop.
Dialog {
    id: control
    padding: 30
    topPadding: 18
    Material.roundedScale: Material.MediumScale

    enter: Transition {
        ParallelAnimation {
            NumberAnimation { property: "opacity"; from: 0.0; to: 1.0; duration: 220; easing.type: Easing.OutCubic }
            NumberAnimation { property: "scale"; from: 0.9; to: 1.0; duration: 320; easing.type: Easing.OutBack; easing.overshoot: 1.1 }
        }
    }
    exit: Transition {
        ParallelAnimation {
            NumberAnimation { property: "opacity"; from: 1.0; to: 0.0; duration: 180; easing.type: Easing.InCubic }
            NumberAnimation { property: "scale"; from: 1.0; to: 0.95; duration: 180; easing.type: Easing.InCubic }
        }
    }

    background: Rectangle {
        radius: 24
        gradient: Gradient {
            GradientStop { position: 0.0; color: "#1b3a72" }
            GradientStop { position: 1.0; color: "#0c1d42" }
        }
        border.width: 1
        border.color: Qt.rgba(1, 1, 1, 0.16)
    }

    Overlay.modal: Rectangle {
        color: "#b3020816"
    }
}
