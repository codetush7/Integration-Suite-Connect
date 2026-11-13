import com.sap.gateway.ip.core.customdev.util.Message

Message processData(Message message) {

    String systemShipment = message.getProperty("SystemShipmentID")?.toString()

    // mapWithDefault(Default = 0)
    String result = (systemShipment && !systemShipment.trim().isEmpty()) ? systemShipment : "0"

    message.setProperty("SystemShipmentValue", result)

    return message
}