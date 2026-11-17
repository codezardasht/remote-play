import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material

Slider {
    property bool firstInFocusChain: false
    property bool lastInFocusChain: false
    property bool sendOutput: false

    id: control
    hoverEnabled: true

    background: Rectangle {
        x: control.leftPadding
        y: control.topPadding + control.availableHeight / 2 - height / 2
        implicitWidth: 200
        implicitHeight: 6
        width: control.horizontal ? control.availableWidth : implicitHeight
        height: control.horizontal ? implicitHeight : control.availableHeight
        radius: 3
        color: Qt.rgba(1, 1, 1, 0.14)

        Rectangle {
            width: control.horizontal ? control.visualPosition * parent.width : parent.width
            height: control.horizontal ? parent.height : (1 - control.visualPosition) * parent.height
            y: control.horizontal ? 0 : control.visualPosition * parent.height
            radius: 3
            gradient: Gradient {
                orientation: Gradient.Horizontal
                GradientStop { position: 0.0; color: "#0070d1" }
                GradientStop { position: 1.0; color: "#5fb3ff" }
            }
        }
    }

    handle: Rectangle {
        x: control.leftPadding + (control.horizontal ? control.visualPosition * (control.availableWidth - width) : (control.availableWidth - width) / 2)
        y: control.topPadding + (control.horizontal ? (control.availableHeight - height) / 2 : control.visualPosition * (control.availableHeight - height))
        implicitWidth: 24
        implicitHeight: 24
        radius: 12
        color: "#ffffff"
        scale: control.pressed ? 1.2 : (control.hovered || control.visualFocus ? 1.1 : 1.0)
        Behavior on scale { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }

        Rectangle {
            anchors.centerIn: parent
            width: parent.width + 14
            height: width
            radius: width / 2
            color: "transparent"
            border.width: 2
            border.color: "#5fb3ff"
            opacity: control.visualFocus || control.pressed ? 0.7 : 0.0
            Behavior on opacity { NumberAnimation { duration: 160 } }
        }
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
        }
    }
}
