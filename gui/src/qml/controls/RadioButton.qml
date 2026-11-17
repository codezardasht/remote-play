import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material

RadioButton {
    property bool firstInFocusChain: false
    property bool lastInFocusChain: false

    id: control
    hoverEnabled: true
    spacing: 14

    indicator: Rectangle {
        id: ring
        implicitWidth: 28
        implicitHeight: 28
        x: control.leftPadding
        y: (control.height - height) / 2
        radius: 14
        color: Qt.rgba(1, 1, 1, control.hovered ? 0.14 : 0.06)
        border.width: 2
        border.color: control.checked ? "#2f95ff" : Qt.rgba(1, 1, 1, 0.35)
        Behavior on border.color { ColorAnimation { duration: 160 } }

        Rectangle {
            anchors.centerIn: parent
            width: 14
            height: 14
            radius: 7
            color: "#2f95ff"
            scale: control.checked ? 1.0 : 0.0
            Behavior on scale { NumberAnimation { duration: 180; easing.type: Easing.OutBack } }
        }
        // PlayStation-style focus ring
        Rectangle {
            anchors.fill: parent
            anchors.margins: -5
            radius: ring.radius + 5
            color: "transparent"
            border.width: 2
            border.color: "#ffffff"
            opacity: control.visualFocus ? 0.9 : 0.0
            Behavior on opacity { NumberAnimation { duration: 160 } }
        }
    }

    contentItem: Text {
        text: control.text
        font: control.font
        color: control.enabled ? "#f2f6fc" : "#7f8ba3"
        verticalAlignment: Text.AlignVCenter
        leftPadding: control.indicator.width + control.spacing
    }

    Keys.onPressed: (event) => {
        switch (event.key) {
        case Qt.Key_Up:
            if (!firstInFocusChain) {
                let item = nextItemInFocusChain(false);
                if (item)
                    item.forceActiveFocus(Qt.TabFocusReason);
                event.accepted = true;
            }
            break;
        case Qt.Key_Down:
            if (!lastInFocusChain) {
                let item = nextItemInFocusChain();
                if (item)
                    item.forceActiveFocus(Qt.TabFocusReason);
                event.accepted = true;
            }
            break;
        case Qt.Key_Return:
            if (visualFocus) {
                toggle();
                toggled();
            }
            event.accepted = true;
            break;
        }
    }
}
