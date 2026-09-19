/*
 * Copyright (C) 2026 Brian Zhu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.app;

import junit.framework.TestCase;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class ThemeTest extends TestCase {

    private File mResDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mResDir = new File("src/main/res");
        if (!mResDir.exists()) {
            mResDir = new File("app/src/main/res");
        }
        assertTrue("res directory must exist", mResDir.exists());
    }

    private Document parseXml(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file);
    }

    private Map<String, String> parseColors() throws Exception {
        File colorsFile = new File(mResDir, "values/colors.xml");
        assertTrue("colors.xml must exist", colorsFile.exists());
        Document doc = parseXml(colorsFile);
        NodeList colorNodes = doc.getElementsByTagName("color");
        Map<String, String> colors = new HashMap<>();
        for (int i = 0; i < colorNodes.getLength(); i++) {
            Element el = (Element) colorNodes.item(i);
            String name = el.getAttribute("name");
            String value = el.getTextContent().trim();
            colors.put(name, value);
        }
        return colors;
    }

    private Map<String, Map<String, String>> parseStyles(File file) throws Exception {
        assertTrue(file.getPath() + " must exist", file.exists());
        Document doc = parseXml(file);
        NodeList styleNodes = doc.getElementsByTagName("style");
        Map<String, Map<String, String>> styles = new HashMap<>();
        for (int i = 0; i < styleNodes.getLength(); i++) {
            Element styleEl = (Element) styleNodes.item(i);
            String styleName = styleEl.getAttribute("name");
            Map<String, String> items = new HashMap<>();
            NodeList itemNodes = styleEl.getElementsByTagName("item");
            for (int j = 0; j < itemNodes.getLength(); j++) {
                Element itemEl = (Element) itemNodes.item(j);
                String itemName = itemEl.getAttribute("name");
                String itemVal = itemEl.getTextContent().trim();
                items.put(itemName, itemVal);
            }
            styles.put(styleName, items);
        }
        return styles;
    }

    public void testNightThemeExplicitTextColorLink() throws Exception {
        File nightThemes = new File(mResDir, "values-night/themes.xml");
        Map<String, Map<String, String>> styles = parseStyles(nightThemes);

        Map<String, String> themeMumla = styles.get("Theme.Mumla");
        assertNotNull("Theme.Mumla must exist in values-night/themes.xml", themeMumla);
        assertTrue("Theme.Mumla in values-night must explicitly define android:textColorLink",
                themeMumla.containsKey("android:textColorLink"));

        String mumlaLinkColor = themeMumla.get("android:textColorLink");
        assertFalse("Theme.Mumla link color must not be black",
                "@color/black".equals(mumlaLinkColor) || "#000000".equals(mumlaLinkColor));
        assertFalse("Theme.Mumla link color must not use md_theme_dark_primary (too dark)",
                "@color/md_theme_dark_primary".equals(mumlaLinkColor));

        Map<String, String> themeOled = styles.get("Theme.Mumla.Oled");
        assertNotNull("Theme.Mumla.Oled must exist in values-night/themes.xml", themeOled);
        assertTrue("Theme.Mumla.Oled must explicitly define android:textColorLink",
                themeOled.containsKey("android:textColorLink"));

        String oledLinkColor = themeOled.get("android:textColorLink");
        assertFalse("Theme.Mumla.Oled link color must not be black",
                "@color/black".equals(oledLinkColor) || "#000000".equals(oledLinkColor));
        assertFalse("Theme.Mumla.Oled link color must not use md_theme_dark_primary",
                "@color/md_theme_dark_primary".equals(oledLinkColor));
    }

    public void testDayThemeExplicitTextColorLink() throws Exception {
        File dayThemes = new File(mResDir, "values/themes.xml");
        Map<String, Map<String, String>> styles = parseStyles(dayThemes);

        Map<String, String> baseMumla = styles.get("Base.Theme.Mumla");
        assertNotNull("Base.Theme.Mumla must exist in values/themes.xml", baseMumla);
        assertTrue("Base.Theme.Mumla must explicitly define android:textColorLink",
                baseMumla.containsKey("android:textColorLink"));

        String linkColor = baseMumla.get("android:textColorLink");
        assertFalse("Base.Theme.Mumla link color must not be white",
                "@color/white".equals(linkColor) || "#ffffff".equalsIgnoreCase(linkColor));
    }

    public void testNightLinkColorContrastRatio() throws Exception {
        Map<String, String> colors = parseColors();
        String linkColorHex = colors.get("md_theme_dark_primary_lighter_more");
        assertNotNull("md_theme_dark_primary_lighter_more must exist in colors.xml", linkColorHex);

        int linkRgb = Integer.parseInt(linkColorHex.replace("#", ""), 16);
        int r = (linkRgb >> 16) & 0xFF;
        int g = (linkRgb >> 8) & 0xFF;
        int b = linkRgb & 0xFF;

        double linkLum = relativeLuminance(r, g, b);
        double blackLum = relativeLuminance(0, 0, 0);
        double darkCardLum = relativeLuminance(0x20, 0x20, 0x20);

        double contrastAgainstBlack = contrastRatio(linkLum, blackLum);
        double contrastAgainstDarkCard = contrastRatio(linkLum, darkCardLum);

        // WCAG AA requires at least 4.5:1 contrast for normal body text
        assertTrue("Link color contrast against black (#000000) must be >= 4.5:1, but was " + contrastAgainstBlack,
                contrastAgainstBlack >= 4.5);
        assertTrue("Link color contrast against dark card (#202020) must be >= 4.5:1, but was " + contrastAgainstDarkCard,
                contrastAgainstDarkCard >= 4.5);
    }

    private static double srgbToLinear(double channel) {
        double c = channel / 255.0;
        return (c <= 0.03928) ? (c / 12.92) : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double relativeLuminance(int r, int g, int b) {
        return 0.2126 * srgbToLinear(r) + 0.7152 * srgbToLinear(g) + 0.0722 * srgbToLinear(b);
    }

    private static double contrastRatio(double lum1, double lum2) {
        double lighter = Math.max(lum1, lum2);
        double darker = Math.min(lum1, lum2);
        return (lighter + 0.05) / (darker + 0.05);
    }
}
