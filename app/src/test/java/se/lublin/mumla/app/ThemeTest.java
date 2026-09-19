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

    private static class StyleDefinition {
        final String name;
        final String parent;
        final Map<String, String> items = new HashMap<>();

        StyleDefinition(String name, String parent) {
            this.name = name;
            this.parent = parent;
        }

        String resolveAttribute(String attrName, Map<String, StyleDefinition> allStyles) {
            if (items.containsKey(attrName)) {
                return items.get(attrName);
            }
            if (parent != null && !parent.isEmpty() && allStyles.containsKey(parent)) {
                return allStyles.get(parent).resolveAttribute(attrName, allStyles);
            }
            return null;
        }
    }

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

    private Map<String, StyleDefinition> parseStyles(File file) throws Exception {
        assertTrue(file.getPath() + " must exist", file.exists());
        Document doc = parseXml(file);
        NodeList styleNodes = doc.getElementsByTagName("style");
        Map<String, StyleDefinition> styles = new HashMap<>();
        for (int i = 0; i < styleNodes.getLength(); i++) {
            Element styleEl = (Element) styleNodes.item(i);
            String styleName = styleEl.getAttribute("name");
            String parentName = styleEl.hasAttribute("parent") ? styleEl.getAttribute("parent") : null;
            StyleDefinition styleDef = new StyleDefinition(styleName, parentName);
            NodeList itemNodes = styleEl.getElementsByTagName("item");
            for (int j = 0; j < itemNodes.getLength(); j++) {
                Element itemEl = (Element) itemNodes.item(j);
                String itemName = itemEl.getAttribute("name");
                String itemVal = itemEl.getTextContent().trim();
                styleDef.items.put(itemName, itemVal);
            }
            styles.put(styleName, styleDef);
        }
        return styles;
    }

    private int[] resolveColorRgb(String colorRefOrHex, Map<String, String> colors) {
        assertNotNull("Color reference or hex must not be null", colorRefOrHex);
        String hex = colorRefOrHex;
        if (colorRefOrHex.startsWith("@color/")) {
            String colorName = colorRefOrHex.substring("@color/".length());
            assertTrue("Referenced color resource '" + colorName + "' must exist in colors.xml",
                    colors.containsKey(colorName));
            hex = colors.get(colorName);
        }

        assertNotNull("Resolved hex string must not be null", hex);
        String cleanHex = hex.replace("#", "").trim();
        assertTrue("Hex string must be 6 or 8 characters: " + hex,
                cleanHex.length() == 6 || cleanHex.length() == 8);

        long val = Long.parseLong(cleanHex, 16);
        int r, g, b;
        if (cleanHex.length() == 6) {
            r = (int) ((val >> 16) & 0xFF);
            g = (int) ((val >> 8) & 0xFF);
            b = (int) (val & 0xFF);
        } else {
            // 8-character ARGB format
            r = (int) ((val >> 16) & 0xFF);
            g = (int) ((val >> 8) & 0xFF);
            b = (int) (val & 0xFF);
        }
        return new int[] { r, g, b };
    }

    public void testNightThemesDynamicTextColorLinkAndContrast() throws Exception {
        Map<String, String> colors = parseColors();
        File nightThemes = new File(mResDir, "values-night/themes.xml");
        Map<String, StyleDefinition> styles = parseStyles(nightThemes);

        StyleDefinition themeMumla = styles.get("Theme.Mumla");
        assertNotNull("Theme.Mumla must exist in values-night/themes.xml", themeMumla);
        String mumlaLinkAttr = themeMumla.resolveAttribute("android:textColorLink", styles);
        assertNotNull("Theme.Mumla in values-night must resolve android:textColorLink", mumlaLinkAttr);

        int[] mumlaRgb = resolveColorRgb(mumlaLinkAttr, colors);
        double mumlaLum = relativeLuminance(mumlaRgb[0], mumlaRgb[1], mumlaRgb[2]);
        double blackLum = relativeLuminance(0, 0, 0);
        double darkCardLum = relativeLuminance(0x20, 0x20, 0x20);

        double mumlaVsBlack = contrastRatio(mumlaLum, blackLum);
        double mumlaVsDarkCard = contrastRatio(mumlaLum, darkCardLum);
        assertTrue("Theme.Mumla link contrast vs #000000 must be >= 4.5:1 (WCAG AA), was " + mumlaVsBlack,
                mumlaVsBlack >= 4.5);
        assertTrue("Theme.Mumla link contrast vs #202020 must be >= 4.5:1 (WCAG AA), was " + mumlaVsDarkCard,
                mumlaVsDarkCard >= 4.5);

        StyleDefinition themeOled = styles.get("Theme.Mumla.Oled");
        assertNotNull("Theme.Mumla.Oled must exist in values-night/themes.xml", themeOled);
        String oledLinkAttr = themeOled.resolveAttribute("android:textColorLink", styles);
        assertNotNull("Theme.Mumla.Oled in values-night must resolve android:textColorLink", oledLinkAttr);

        int[] oledRgb = resolveColorRgb(oledLinkAttr, colors);
        double oledLum = relativeLuminance(oledRgb[0], oledRgb[1], oledRgb[2]);
        double oledVsBlack = contrastRatio(oledLum, blackLum);
        double oledVsDarkCard = contrastRatio(oledLum, darkCardLum);
        assertTrue("Theme.Mumla.Oled link contrast vs #000000 must be >= 4.5:1 (WCAG AA), was " + oledVsBlack,
                oledVsBlack >= 4.5);
        assertTrue("Theme.Mumla.Oled link contrast vs #202020 must be >= 4.5:1 (WCAG AA), was " + oledVsDarkCard,
                oledVsDarkCard >= 4.5);
    }

    public void testDayThemeDynamicTextColorLinkAndContrast() throws Exception {
        Map<String, String> colors = parseColors();
        File dayThemes = new File(mResDir, "values/themes.xml");
        Map<String, StyleDefinition> styles = parseStyles(dayThemes);

        StyleDefinition baseMumla = styles.get("Base.Theme.Mumla");
        assertNotNull("Base.Theme.Mumla must exist in values/themes.xml", baseMumla);
        String baseLinkAttr = baseMumla.resolveAttribute("android:textColorLink", styles);
        assertNotNull("Base.Theme.Mumla must resolve android:textColorLink", baseLinkAttr);

        int[] dayRgb = resolveColorRgb(baseLinkAttr, colors);
        double dayLum = relativeLuminance(dayRgb[0], dayRgb[1], dayRgb[2]);
        double whiteLum = relativeLuminance(0xFF, 0xFF, 0xFF);

        double dayVsWhite = contrastRatio(dayLum, whiteLum);
        assertTrue("Base.Theme.Mumla link contrast vs #FFFFFF must be >= 4.5:1 (WCAG AA), was " + dayVsWhite,
                dayVsWhite >= 4.5);
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
