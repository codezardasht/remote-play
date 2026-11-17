import QtQuick
import QtQuick.Controls
import QtQuick.Controls.impl
import QtQuick.Layouts

// Rounded "pill" button in the PlayStation style: translucent at rest,
// solid white with dark text when highlighted, smooth scale/colour transitions.
Button {
    id: control
    property url glyph: ""          // optional controller button glyph shown left of the text
    property bool primary: false    // primary action uses the PlayStation blue
    property bool highlighted2: false // extra externally controlled highlight
    icon.width: 26
    icon.height: 26
    readonly property bool lit: visualFocus || hovered || highlighted2

    implicitHeight: 52
    leftPadding: glyph.toString() ? 14 : 26
    rightPadding: 26
    topPadding: 0
    bottomPadding: 0
    font.pixelSize: 19
    font.weight: Font.Medium
    hoverEnabled: true

    scale: pressed ? 0.95 : (lit ? 1.04 : 1.0)
    Behavior on scale { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }

    contentItem: RowLayout {
        spacing: 10
        Image {
            visible: control.glyph.toString() !== ""
            Layout.preferredWidth: 28
            Layout.preferredHeight: 28
            Layout.alignment: Qt.AlignVCenter
            sourceSize: Qt.size(28, 28)
            source: control.glyph
        }
        IconImage {
            visible: control.icon.source.toString() !== ""
            Layout.preferredWidth: control.icon.width
            Layout.preferredHeight: control.icon.height
            Layout.alignment: Qt.AlignVCenter
            sourceSize: Qt.size(control.icon.width, control.icon.height)
            source: control.icon.source
            color: control.lit && !control.primary ? "#0b1530" : "#ffffff"
        }
        Text {
            visible: text !== ""
            Layout.alignment: Qt.AlignVCenter
            text: control.text
            font: control.font
            color: control.lit && !control.primary ? "#0b1530" : "#ffffff"
            opacity: control.enabled ? 1.0 : 0.4
            Behavior on color { ColorAnimation { duration: 160 } }
        }
    }

    background: Rectangle {
        implicitWidth: 120
        implicitHeight: 52
        radius: height / 2
        opacity: control.enabled ? 1.0 : 0.45
        color: control.primary
               ? (control.lit ? "#2f95ff" : "#0070d1")
               : (control.lit ? "#ffffff" : Qt.rgba(1, 1, 1, 0.10))
        border.width: control.lit ? 0 : 1
        border.color: Qt.rgba(1, 1, 1, 0.18)
        Behavior on color { ColorAnimation { duration: 160 } }

        // soft outer glow when highlighted
        Rectangle {
            anchors.fill: parent
            anchors.margins: -5
            radius: height / 2
            color: "transparent"
            border.width: 2
            border.color: control.primary ? "#6fb8ff" : "#ffffff"
            opacity: control.lit ? 0.35 : 0.0
            Behavior on opacity { NumberAnimation { duration: 180 } }
        }
    }
}
