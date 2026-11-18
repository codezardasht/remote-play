import QtQuick
import QtQuick.Controls

// Connection progress bar with a percentage. Each connection step sets `floor` (where the bar jumps to)
// and `ceiling` (how far it may creep while the step runs). The bar eases toward the ceiling without
// reaching it, so it keeps moving during slow steps but only shows 100% once `done` is set.
Item {
    id: root
    property real floor: 0
    property real ceiling: 0.5
    // Fraction of the remaining gap closed per second; lower for steps that can take long
    property real creepRate: 0.2
    property bool done: false
    property string step: ""
    property real value: 0
    property bool running: visible

    implicitWidth: 360
    implicitHeight: column.implicitHeight

    function setStep(newFloor, newCeiling, rate, label) {
        floor = newFloor;
        ceiling = newCeiling;
        creepRate = rate;
        if (label !== undefined)
            step = label;
        if (value < floor)
            value = floor;
    }

    function reset() {
        done = false;
        value = 0;
        floor = 0;
        ceiling = 0.5;
        creepRate = 0.2;
    }

    onDoneChanged: if (done) value = 1

    Timer {
        interval: 100
        repeat: true
        running: root.running && !root.done
        onTriggered: {
            if (root.value < root.ceiling)
                root.value = Math.min(root.ceiling, root.value + (root.ceiling - root.value) * root.creepRate * interval / 1000);
        }
    }

    Column {
        id: column
        width: parent.width
        spacing: 12

        Item {
            width: parent.width
            height: 6

            Rectangle {
                anchors.fill: parent
                radius: height / 2
                color: "#33ffffff"
            }
            Rectangle {
                height: parent.height
                width: parent.width * root.value
                radius: height / 2
                color: "#ffffff"
                Behavior on width { NumberAnimation { duration: 250; easing.type: Easing.OutCubic } }
            }
        }

        Label {
            anchors.horizontalCenter: parent.horizontalCenter
            font.pixelSize: 20
            font.bold: true
            text: Math.floor(root.value * 100) + "%"
        }

        Label {
            anchors.horizontalCenter: parent.horizontalCenter
            visible: text !== ""
            font.pixelSize: 14
            opacity: 0.8
            text: root.step
        }
    }
}
