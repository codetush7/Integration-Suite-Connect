import com.sap.gateway.ip.core.customdev.util.Message

Message processData(Message message) {

    String shippingPoint = message.getProperty("ShippingPointType")?.toString()
    String shippingLocation = message.getProperty("ShippingLocationCode")?.toString()

    String finalValue = ""

    if (!"HUB".equalsIgnoreCase(shippingPoint)) {
        finalValue = shippingLocation
    }

    message.setProperty("FinalShippingLocation", finalValue)

    return message
}