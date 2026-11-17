import QtQuick
import QtQuick.Controls
import QtQuick.Controls.impl

// Circular icon button for the top bar, with an optional controller glyph badge
// and a tooltip that fades in on hover.
Button {
    id: control
    property url glyph: ""
    property string tip: ""
    property string iconText: ""   // text glyph used when no icon source is given
    readonly property bool lit: visualFocus || hovered

    implicitWidth: 60
    implicitHeight: 60
    padding: 0
    hoverEnabled: true
    focusPolicy: Qt.NoFocus
    icon.width: 28
    icon.height: 28

    scale: pressed ? 0.92 : (lit ? 1.08 : 1.0)
    Behavior on scale { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }

    contentItem: Item {
        IconImage {
            anchors.centerIn: parent
            visible: control.icon.source.toString() !== ""
            width: control.icon.width
            height: control.icon.height
            sourceSize: Qt.size(width, height)
            source: control.icon.source
            color: control.lit ? "#0b1530" : "#ffffff"
        }
        Text {
            anchors.centerIn: parent
            visible: control.iconText !== ""
            text: control.iconText
            font.pixelSize: 30
            color: control.lit ? "#0b1530" : "#ffffff"
        }
    }

    background: Rectangle {
        radius: width / 2
        color: control.lit ? "#ffffff" : Qt.rgba(1, 1, 1, 0.08)
        border.width: control.lit ? 0 : 1
        border.color: Qt.rgba(1, 1, 1, 0.16)
        Behavior on color { ColorAnimation { duration: 160 } }
    }

    Image {
        visible: control.glyph.toString() !== ""
        anchors { right: parent.right; bottom: parent.bottom; rightMargin: -4; bottomMargin: -4 }
        width: 20
        height: 20
        sourceSize: Qt.size(width, height)
        source: control.glyph
    }

    ToolTip.visible: hovered && tip !== ""
    ToolTip.text: tip
    ToolTip.delay: 400
}
