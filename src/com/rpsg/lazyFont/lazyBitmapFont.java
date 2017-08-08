package com.rpsg.lazyFont;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeType;
import com.badlogic.gdx.graphics.g2d.freetype.FreeType.Face;
import com.badlogic.gdx.graphics.g2d.freetype.FreeType.SizeMetrics;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeBitmapFontData;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.GlyphAndBitmap;
import com.badlogic.gdx.utils.GdxRuntimeException;

import java.lang.reflect.Field;

/**
 * GDX-LAZY-FONT for LibGDX 1.5.0+<br/>
 * <b>Auto generate & manage your bitmapfont without pre-generate.</b>
 *
 * @author dingjibang
 * @version 2.1.5
 * @see https://github.com/dingjibang/GDX-LAZY-FONT
 */
public class LazyBitmapFont extends BitmapFont {

	private FreeTypeFontGenerator generator;
	private FreeTypeBitmapFontData data;
	private FreeTypeFontParameter parameter;

	private static FreeTypeFontGenerator GLOBAL_GEN = null;

	public static void setGlobalGenerator(FreeTypeFontGenerator generator) {
		GLOBAL_GEN = generator;
	}

	public LazyBitmapFont(int fontSize) {
		this(GLOBAL_GEN, fontSize);
	}

	public LazyBitmapFont(FreeTypeFontGenerator generator, int fontSize) {
		if (generator == null)
			throw new GdxRuntimeException("lazyBitmapFont global generator must be not null to use this constructor.");
		this.generator = generator;
		FreeTypeFontParameter param = new FreeTypeFontParameter();
		param.size = fontSize;
		this.parameter = param;
		this.data = new LazyBitmapFontData(generator, fontSize, this);
		try {
			Field f = getClass().getSuperclass().getDeclaredField("data");
			f.setAccessible(true);
			f.set(this, data);
		} catch (Exception e) {
			e.printStackTrace();
		}

		genrateData();
	}

	private void genrateData() {
		Face face = null;
		try {
			Field field = generator.getClass().getDeclaredField("face");
			field.setAccessible(true);
			face = (Face) field.get(generator);
		} catch (Exception e) {
			e.printStackTrace();
			return;
		}

		// set general font data
		SizeMetrics fontMetrics = face.getSize().getMetrics();

		// Set space glyph.
		Glyph spaceGlyph = data.getGlyph(' ');
		if (spaceGlyph == null) {
			spaceGlyph = new Glyph();
			spaceGlyph.xadvance = (int) data.spaceWidth;
			spaceGlyph.id = (int) ' ';
			data.setGlyph(' ', spaceGlyph);
		}
		if (spaceGlyph.width == 0)
			spaceGlyph.width = (int) (spaceGlyph.xadvance + data.padRight);

		// set general font data
		data.flipped = parameter.flip;
		data.ascent = FreeType.toInt(fontMetrics.getAscender());
		data.descent = FreeType.toInt(fontMetrics.getDescender());
		data.lineHeight = FreeType.toInt(fontMetrics.getHeight());

		// determine x-height
		for (char xChar : data.xChars) {
			if (!face.loadChar(xChar, FreeType.FT_LOAD_DEFAULT))
				continue;
			data.xHeight = FreeType.toInt(face.getGlyph().getMetrics().getHeight());
			break;
		}
		if (data.xHeight == 0)
			throw new GdxRuntimeException("No x-height character found in font");
		for (char capChar : data.capChars) {
			if (!face.loadChar(capChar, FreeType.FT_LOAD_DEFAULT))
				continue;
			data.capHeight = FreeType.toInt(face.getGlyph().getMetrics().getHeight());
			break;
		}

		// determine cap height
		if (data.capHeight == 1)
			throw new GdxRuntimeException("No cap character found in font");
		data.ascent = data.ascent - data.capHeight;
		data.down = -data.lineHeight;
		if (parameter.flip) {
			data.ascent = -data.ascent;
			data.down = -data.down;
		}

	}

	@Override
	public void dispose() {
		setOwnsTexture(true);
		super.dispose();
		data.dispose();

	}

	public static class LazyBitmapFontData extends FreeTypeBitmapFontData {

		private FreeTypeFontGenerator generator;
		private int fontSize;
		private LazyBitmapFont font;
		private int page = 1;

		//modified by STH99 on 2017-8-8
		private Pixmap oneBigMap;
		private Texture oneBigTexture;
		private int currX, currY, lineH;
		private int mapW, mapH;

		public LazyBitmapFontData(FreeTypeFontGenerator generator, int fontSize, LazyBitmapFont lbf) {
			this.generator = generator;
			this.fontSize = fontSize;
			this.font = lbf;
			//modified by STH99 on 2017-8-8
			//所有生成的分开的材质整合进一个大材质
			//GL在绘制时不用再切换材质了，节省每个字符间切换材质花费的时间
			//性能提升1倍多（改前30帧，现在60帧）
			//注意，单张Texture字符数有限制，如果字库大，请改成多张Texture
			this.mapW = 0x1000;
			this.mapH = 0x1000;
			this.oneBigMap = new Pixmap(mapW, mapH, Format.RGBA8888);
			this.oneBigTexture = new Texture(oneBigMap);
			this.currX = 0;
			this.currY = 0;
			this.lineH = 0;
		}

		public Glyph getGlyph(char ch) {
			Glyph glyph = super.getGlyph(ch);
			if (glyph == null && ch != 0)
				glyph = generateGlyph(ch);
			return glyph;
		}

		protected Glyph generateGlyph(char ch) {
			GlyphAndBitmap gab = generator.generateGlyphAndBitmap(ch, fontSize, false);
			if (gab == null || gab.bitmap == null)
				return null;

			//modified by STH99 on 2017-6-10
			Pixmap map = gab.bitmap.getPixmap(Format.RGBA8888, Color.WHITE, 1.0f);
			int w = map.getWidth();
			int h = map.getHeight();
			TextureRegion rg = new TextureRegion(oneBigTexture, currX, currY, w, h);
			oneBigTexture.draw(map, currX, currY);
			if (currX + w >= mapW) {
				currX = 0;
				currY += lineH;
				lineH = 0;
			} else {
				currX += w;
				lineH = lineH > h ? lineH : h;
			}
			map.dispose();

			font.getRegions().add(rg);

			gab.glyph.page = page++;
			super.setGlyph(ch, gab.glyph);
			setGlyphRegion(gab.glyph, rg);

			return gab.glyph;
		}

	}

}