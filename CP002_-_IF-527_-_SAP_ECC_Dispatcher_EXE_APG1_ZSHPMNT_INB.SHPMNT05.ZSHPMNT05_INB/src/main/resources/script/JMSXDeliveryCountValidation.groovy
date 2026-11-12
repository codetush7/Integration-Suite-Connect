import com.sap.gateway.ip.core.customdev.util.Message

Message processData(Message message) {

    def deliveryCount = message.getHeaders().get("JMSXDeliveryCount")


    message.setProperty("hasJMSXDeliveryCount", "0")

        if (deliveryCount != null) {
            message.setProperty("hasJMSXDeliveryCount", "1")
        }


    return message
}