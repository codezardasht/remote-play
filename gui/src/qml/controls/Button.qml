import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material

Button {
    property bool firstInFocusChain: false
    property bool lastInFocusChain: false
    property bool sendOutput: false

    // PlayStation-style focus: solid white pill with dark text
    Material.background: visualFocus ? "#ffffff" : undefined
    Material.foreground: visualFocus ? "#0b1530" : undefined
    scale: visualFocus ? 1.03 : 1.0
    Behavior on scale { NumberAnimation { duration: 160; easing.type: Easing.OutCubic } }

    id: control
    hoverEnabled: true

    background: Rectangle {
        implicitWidth: 100
        implicitHeight: 48
        radius: height / 2
        color: control.visualFocus ? "#ffffff"
             : control.pressed ? Qt.rgba(1, 1, 1, 0.22)
             : control.hovered ? Qt.rgba(1, 1, 1, 0.16) : Qt.rgba(1, 1, 1, 0.09)
        border.width: control.visualFocus ? 0 : 1
        border.color: Qt.rgba(1, 1, 1, 0.16)
        opacity: control.enabled ? 1.0 : 0.45
        Behavior on color { ColorAnimation { duration: 150 } }
    }

    Component.onDestruction: {
        if (visualFocus) {
            let item = nextItemInFocusChain();
            if (item)
                item.forceActiveFocus(Qt.TabFocusReason);
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
        case Qt.Key_Return:
            if (visualFocus) {
                clicked();
            }
            event.accepted = true;
            break;
        }
    }
}
