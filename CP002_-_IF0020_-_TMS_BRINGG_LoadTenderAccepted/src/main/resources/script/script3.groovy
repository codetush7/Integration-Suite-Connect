import com.sap.gateway.ip.core.customdev.util.Message

def Message processData(Message message) {

    /*
     * Read values from Exchange Properties
     */
    String shippingPointType   = message.getProperty("ShippingPointType")?.toString()?.trim() ?: ""
    String shippingLocationCode = message.getProperty("ShippingLocationCode")?.toString()?.trim() ?: ""
    String stopSequenceNumber  = message.getProperty("StopSequenceNumber")?.toString()?.trim() ?: ""
    String commodityCode       = message.getProperty("CommodityCode")?.toString()?.trim() ?: ""

    /*
     * Equivalent of fixValues + ifWithoutElse
     */

    // ShippingPointType = HUB ? CommodityCode : ""
    String dockCode = ""
    if ("HUB".equalsIgnoreCase(shippingPointType)) {
        dockCode = commodityCode
    }

    // StopSequenceNumber != 1 ? ShippingLocationCode : ""
    String locationCode = ""
    if (!"1".equals(stopSequenceNumber)) {
        locationCode = shippingLocationCode
    }

    /*
     * Build CIS Request
     */
    String cisRequest =
"""<cis:findEntities xmlns:cis="http://www.i2.com/cis">
    <cis:EntityType>DockType</cis:EntityType>
    <Select>
        <Name>BusinessHours</Name>
    </Select>
    <cis:Filter>
        <cis:Name>ShippingLocationCode</cis:Name>
        <cis:Op>Eq</cis:Op>
        <cis:Value>${locationCode}</cis:Value>
    </cis:Filter>
    <cis:Filter>
        <Name>DockCode</Name>
        <Op>Eq</Op>
        <Value>${dockCode}</Value>
    </cis:Filter>
</cis:findEntities>"""

    /*
     * Store final XML in a property
     */
    message.setProperty("DockTypeRequest", cisRequest)

    /*
     * Optional: Equivalent of strIndexOf
     */
    String searchVar = message.getProperty("SearchVar")?.toString() ?: ""

    if (!searchVar.isEmpty()) {
        message.setProperty("SearchIndex", cisRequest.indexOf(searchVar))
    }

    return message
}