import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material

CheckBox {
    property bool firstInFocusChain: false
    property bool lastInFocusChain: false
    property bool sendOutput: false

    id: control
    hoverEnabled: true
    spacing: 14

    indicator: Rectangle {
        id: track
        implicitWidth: 54
        implicitHeight: 30
        x: control.leftPadding
        y: (control.height - height) / 2
        radius: height / 2
        color: control.checked ? "#2f95ff" : Qt.rgba(1, 1, 1, control.hovered ? 0.22 : 0.14)
        border.width: 1
        border.color: control.checked ? "#6fb8ff" : Qt.rgba(1, 1, 1, 0.18)
        Behavior on color { ColorAnimation { duration: 180 } }

        Rectangle {
            width: 24
            height: 24
            radius: 12
            y: 3
            x: control.checked ? track.width - width - 3 : 3
            color: "#ffffff"
            Behavior on x { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }
        }
        // PlayStation-style focus ring
        Rectangle {
            anchors.fill: parent
            anchors.margins: -5
            radius: track.radius + 5
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
        wrapMode: Text.WordWrap
    }

    Keys.onPressed: (event) => {
        switch (event.key) {
        case Qt.Key_Up:
            if (!firstInFocusChain) {
                let item = nextItemInFocusChain(false);
                if (item)
                    item.forceActiveFocus(Qt.TabFocusReason);
                if(!sendOutput)
                    event.accepted = true;
            }
            break;
        case Qt.Key_Down:
            if (!lastInFocusChain) {
                let item = nextItemInFocusChain();
                if (item)
                    item.forceActiveFocus(Qt.TabFocusReason);
                if(!sendOutput)
                    event.accepted = true;
            }
            break;
        case Qt.Key_Return:
            if (visualFocus) {
                toggle();
                clicked();
            }
            event.accepted = true;
            break;
        }
    }
}
