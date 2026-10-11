package shiningmask;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RemoteServerTest {
    @Test
    void parsesForms() {
        Map<String, String> p = RemoteServer.parseForm("a=text&s=DROP+IT%21&n=3&flag");
        assertEquals("text", p.get("a"));
        assertEquals("DROP IT!", p.get("s"));
        assertEquals("3", p.get("n"));
        assertEquals("", p.get("flag"));
        assertEquals(0, RemoteServer.parseForm("").size());
    }

    @Test
    void escapesJson() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", RemoteServer.json("a\"b\\c\n\u0001"));
        assertEquals("null", RemoteServer.json(null));
    }
}
