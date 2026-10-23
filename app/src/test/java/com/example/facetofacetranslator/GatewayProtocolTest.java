package com.example.facetofacetranslator;
import org.junit.Test;
import static org.junit.Assert.*;
public class GatewayProtocolTest {
    @Test public void acceptsSelectedDestinations() {
        assertEquals("+8801712345678",GatewayProtocol.number("+880 17-12345678"));
        assertEquals("+60123456789",GatewayProtocol.number("+60123456789"));
    }
    @Test public void rejectsOtherDestinationsAndMalformedNumbers() {
        for(String value:new String[]{"+11234567890","60123456789","+600","+60123456789?x"}) {
            try{GatewayProtocol.number(value);fail(value);}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void requiresHttpsExceptLoopback() {
        assertEquals("https://example.com",GatewayProtocol.baseUrl("https://example.com/"));
        assertEquals("http://127.0.0.1:8765",GatewayProtocol.baseUrl("http://127.0.0.1:8765"));
        for(String value:new String[]{"http://example.com","https://x/a","https://user:secret@x","https://x/?token=x","https://x/#x","http://localhost.evil","https://x:70000"}) {
            try{GatewayProtocol.baseUrl(value);fail(value);}catch(IllegalArgumentException expected){}
        }
    }
}
