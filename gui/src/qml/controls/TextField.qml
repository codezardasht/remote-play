import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material

TextField {
    property bool firstInFocusChain: false
    property bool lastInFocusChain: false
    property bool sendOutput: false
    readOnly: true

    onActiveFocusChanged: {
        if (!activeFocus)
            readOnly = true;
    }

    id: control
    hoverEnabled: true
    leftPadding: 16
    rightPadding: 16
    color: "#f2f6fc"
    placeholderTextColor: "#7f8ba3"
    selectionColor: "#2f95ff"
    verticalAlignment: TextInput.AlignVCenter

    background: Rectangle {
        implicitWidth: 200
        implicitHeight: 52
        radius: 14
        color: !control.readOnly ? Qt.rgba(1, 1, 1, 0.14)
             : (control.hovered || control.activeFocus) ? Qt.rgba(1, 1, 1, 0.10) : Qt.rgba(1, 1, 1, 0.06)
        border.width: !control.readOnly ? 2 : 1
        border.color: !control.readOnly ? "#2f95ff" : Qt.rgba(1, 1, 1, 0.14)
        Behavior on color { ColorAnimation { duration: 150 } }
        // PlayStation-style focus ring
        Rectangle {
            anchors.fill: parent
            anchors.margins: -5
            radius: parent.radius + 5
            color: "transparent"
            border.width: 2
            border.color: "#ffffff"
            opacity: (control.activeFocus && control.readOnly) ? 0.9 : 0.0
            Behavior on opacity { NumberAnimation { duration: 160 } }
        }
    }

    Keys.onPressed: (event) => {
        switch (event.key) {
        case Qt.Key_Up:
            if (!firstInFocusChain && readOnly) {
                let item = nextItemInFocusChain(false);
                if (item)
                    item.forceActiveFocus(Qt.TabFocusReason);
                if(!sendOutput)
                    event.accepted = true;
            }
            break;
        case Qt.Key_Down:
            if (!lastInFocusChain && readOnly) {
                let item = nextItemInFocusChain();
                if (item)
                    item.forceActiveFocus(Qt.TabFocusReason);
                if(!sendOutput)
                    event.accepted = true;
            }
            break;
        case Qt.Key_Return:
            if (readOnly) {
                readOnly = false;
                Qt.inputMethod.show();
                event.accepted = true;
            } else {
                readOnly = true;
            }
            break;
        case Qt.Key_Escape:
            if (!readOnly) {
                readOnly = true;
                editingFinished();
                event.accepted = true;
            }
            break;
        }
    }

    MouseArea {
        anchors.fill: parent
        enabled: parent.readOnly
        onClicked: {
            parent.forceActiveFocus(Qt.TabFocusReason);
            parent.readOnly = false;
            Qt.inputMethod.show();
        }
    }
}
