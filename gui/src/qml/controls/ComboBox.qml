import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Controls.impl

ComboBox {
    property bool firstInFocusChain: false
    property bool lastInFocusChain: false
    implicitContentWidthPolicy: ComboBox.WidestText

    id: control
    implicitHeight: 52
    leftPadding: 18
    rightPadding: 46
    hoverEnabled: true

    background: Rectangle {
        implicitWidth: 200
        implicitHeight: 52
        radius: 14
        color: control.pressed ? Qt.rgba(1, 1, 1, 0.16)
             : (control.hovered || control.visualFocus) ? Qt.rgba(1, 1, 1, 0.12) : Qt.rgba(1, 1, 1, 0.06)
        border.width: 1
        border.color: control.popup.visible ? "#5fb3ff" : Qt.rgba(1, 1, 1, 0.14)
        Behavior on color { ColorAnimation { duration: 150 } }
        // PlayStation-style focus ring
        Rectangle {
            anchors.fill: parent
            anchors.margins: -5
            radius: parent.radius + 5
            color: "transparent"
            border.width: 2
            border.color: "#ffffff"
            opacity: control.visualFocus ? 0.9 : 0.0
            Behavior on opacity { NumberAnimation { duration: 160 } }
        }
    }

    contentItem: Text {
        text: control.displayText
        font: control.font
        color: control.enabled ? "#f2f6fc" : "#7f8ba3"
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }

    indicator: Text {
        x: control.width - width - 18
        y: (control.height - height) / 2
        text: "⌄"
        color: "#9fb4d8"
        font.pixelSize: 22
        rotation: control.popup.visible ? 180 : 0
        Behavior on rotation { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
    }

    popup.background: Rectangle {
        radius: 14
        color: "#13284f"
        border.width: 1
        border.color: Qt.rgba(1, 1, 1, 0.16)
    }

    Keys.onPressed: (event) => {
        switch (event.key) {
        case Qt.Key_Up:
            if (!popup.visible) {
                let item = nextItemInFocusChain(false);
                if (!firstInFocusChain && item)
                    item.forceActiveFocus(Qt.TabFocusReason);
                event.accepted = true;
            }
            break;
        case Qt.Key_Down:
            if (!popup.visible) {
                let item = nextItemInFocusChain();
                if (!lastInFocusChain && item)
                    item.forceActiveFocus(Qt.TabFocusReason);
                event.accepted = true;
            }
            break;
        case Qt.Key_Return:
            if (popup.visible) {
                activated(highlightedIndex);
                popup.close();
            } else {
                popup.open();
            }
            event.accepted = true;
            break;
        }
    }

    Keys.onReleased: (event) => {
        if (event.key == Qt.Key_Return)
            event.accepted = true;
    }
}
