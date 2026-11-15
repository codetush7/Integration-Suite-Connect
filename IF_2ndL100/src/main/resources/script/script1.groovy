import com.sap.gateway.ip.core.customdev.util.Message
import groovy.xml.*
import groovy.json.*
import java.math.RoundingMode

def Message processData(Message message) {

    def body = message.getBody(String)

    def xml = new XmlSlurper().parseText(body)

    def header = xml.IDOC.E1EDK01

    if (!header || header.size() == 0) {
        throw new Exception("Order Header Missing")
    }

    def belnr = header.BELNR.text()
    def currency = header.WAERK.text()
    def customer = header.KUNNR.text()

    def rawDate = header.AUDAT.text()

    def orderDate =
            rawDate[0..3] + "-" +
            rawDate[4..5] + "-" +
            rawDate[6..7]

    def grouped = [:]

    xml.IDOC.E1EDP01.each { item ->

        BigDecimal qty =
                new BigDecimal(item.MENGE.text())

        if(qty == 0)
            return

        String plant = item.WERKS.text()

        if(!grouped.containsKey(plant)) {
            grouped[plant] = []
        }

        grouped[plant] << item
    }

    def invoices = []

    grouped.each { plant, items ->

        BigDecimal orderTotal = 0.00

        def lineItems = []

        items.each { item ->

            BigDecimal qty =
                    new BigDecimal(item.MENGE.text())

            BigDecimal price =
                    new BigDecimal(item.VPREI.text())

            BigDecimal lineTotal =
                    (qty * price).setScale(2,RoundingMode.HALF_UP)

            orderTotal += lineTotal

            lineItems << [
                    line      : item.POSEX.text(),
                    material  : item.MATNR.text(),
                    qty       : qty,
                    unitPrice : price.setScale(2),
                    lineTotal : lineTotal
            ]
        }

        String country = items[0].LAND1.text()

        String taxRegion
        Boolean reviewRequired = false

        if(!country) {
            taxRegion = "UNKNOWN"
            reviewRequired = true
        }
        else if(country == "US") {
            taxRegion = "DOMESTIC"
        }
        else {
            taxRegion = "INTERNATIONAL"
        }

        def invoice = [
                invoiceRef : "${belnr}-${plant}",
                orderDate  : orderDate,
                customer   : customer,
                currency   : currency,
                plant      : plant,
                taxRegion  : taxRegion,
                lineItems  : lineItems,
                orderTotal :
                    orderTotal.setScale(2,
                    RoundingMode.HALF_UP)
        ]

        if(reviewRequired) {
            invoice.reviewRequired = true
        }

        invoices << invoice
    }

    message.setBody(
            JsonOutput.prettyPrint(
                    JsonOutput.toJson(invoices)
            )
    )

    return message
}