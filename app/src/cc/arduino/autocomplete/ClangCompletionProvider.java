/*
 * This file is part of Arduino.
 *
 * Copyright 2017 Arduino LLC (http://www.arduino.cc/)
 *
 * Arduino is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 * As a special exception, you may use this file as part of a free software
 * library without restriction.  Specifically, if other files instantiate
 * templates or use macros or inline functions from this file, or you compile
 * this file and link it with other files to produce an executable, this
 * file does not by itself cause the resulting executable to be covered by
 * the GNU General Public License.  This exception does not however
 * invalidate any other reasons why the executable file might be covered by
 * the GNU General Public License.
 */

package cc.arduino.autocomplete;

import java.util.ArrayList;
import java.util.List;

import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;

import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.autocomplete.FunctionCompletion;
import org.fife.ui.autocomplete.LanguageAwareCompletionProvider;
import org.fife.ui.autocomplete.ParameterizedCompletion.Parameter;
import org.fife.ui.autocomplete.TemplateCompletion;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;

import processing.app.Editor;
import processing.app.EditorTab;

public class ClangCompletionProvider extends LanguageAwareCompletionProvider {

  private Editor editor;
  private String completeCache;
  private int completeCacheLine;
  private int completeCacheColumn;

  public ClangCompletionProvider(Editor e, DefaultCompletionProvider cp) {
    super(cp);
    editor = e;
    cp.setParameterizedCompletionParams('(', ", ", ')');
  }

  @Override
  protected List<Completion> getCompletionsImpl(JTextComponent textarea) {

    List<Completion> res = new ArrayList<>();

    // Retrieve current line and column
    EditorTab tab = editor.getCurrentTab();
    int line, col;
    try {
      int pos = tab.getTextArea().getCaretPosition();
      line = tab.getTextArea().getLineOfOffset(pos);
      col = pos - tab.getTextArea().getLineStartOffset(line);
      line++;
      col++;
    } catch (BadLocationException e1) {
      // Should never happen...
      e1.printStackTrace();
      return res;
    }

    try {
      // Run codecompletion engine
      String out = completeCache;
      if (completeCacheLine != line || (completeCacheColumn != (col + 1)) && (completeCacheColumn != (col - 1))) {
        out = editor.getSketchController()
          .codeComplete(tab.getSketchFile(), line, col);
        completeCache = out;
        completeCacheLine = line;
      }
      completeCacheColumn = col;

      // Parse engine output and build code completions
      ObjectMapper mapper = new ObjectMapper();
      ArduinoCompletionsList allCc;
      try {
        allCc = mapper.readValue(out, ArduinoCompletionsList.class);
      } catch (JsonParseException e) {
        System.err.println("Error parsing autocomplete output:");
        System.err.println();
        int begin = (int) e.getLocation().getCharOffset() - 100;
        if (begin < 0) {
          begin = 0;
        }
        int end = begin + 100;
        if (end >= out.length()) {
          System.err.println(out.substring(begin));
        } else {
          System.err.println(out.substring(begin, end));
        }
        System.err.println();
        e.printStackTrace();
        return res;
      }

      String enteredText = getAlreadyEnteredText(textarea);
      for (ArduinoCompletion cc : allCc) {
        // Filter completions based on already entered text
        if (!cc.getCompletion().getTypedText().startsWith(enteredText)) {
          continue;
        }

        Completion completion = createCompletionFromArduinoCompletion(cc);
        res.add(completion);
      }
      return res;
    } catch (Exception e) {
      e.printStackTrace();
      return res;
    }
  }

  private Completion createCompletionFromArduinoCompletion(ArduinoCompletion cc) {
    if (cc.type.equals("Function")) {
      List<Parameter> params = new ArrayList<>();
      int i = 0;
      String shortDesc = "(";
      for (CompletionChunk chunk : cc.completion.chunks) {
        if (chunk.placeholder != null) {
          ArduinoParameter p = cc.parameters.get(i);
          params.add(new Parameter(p.type, p.name));
          shortDesc += (p.name.equals("") ? p.type : p.name) + ", ";
          i++;
        }
      }
      int lastComma = shortDesc.lastIndexOf(",");
      if (lastComma > 0) {
        shortDesc = shortDesc.substring(0, lastComma);
      }
      shortDesc += ")";

      FunctionCompletion compl = new FunctionCompletion(this,
          cc.getCompletion().getTypedText(),
          cc.getCompletion().getResultType());
      compl.setParams(params);
      compl.setShortDescription(shortDesc + " : "
                                + cc.getCompletion().getResultType());
      return compl;
    } else {

      String returnType = "";
      String typedText = null;
      String template = "";
      String shortDesc = "";

      for (CompletionChunk chunk : cc.completion.chunks) {
        if (chunk.t != null) {
          template += chunk.t;
          shortDesc += chunk.t;
        }
        if (chunk.res != null) {
          returnType = chunk.res;
        }
        if (chunk.typedtext != null) {
          template += chunk.typedtext;
          shortDesc += chunk.typedtext;
          typedText = chunk.typedtext;
        }
        if (chunk.placeholder != null) {
          String[] spl = chunk.placeholder.split(" ");
          template += "${" + spl[spl.length - 1] + "}";
          shortDesc += spl[spl.length - 1];
        }
        if (chunk.info != null) {
          // System.out.println("INFO: "+chunk.info);
        }
      }
      template += "${cursor}";
      System.out.println("TEMPLATE: " + template);
      System.out.println("SHORT: " + shortDesc);
      if (!returnType.isEmpty()) {
        shortDesc += " : " + returnType;
      }
      return new TemplateCompletion(this, typedText, shortDesc, template);
    }
  }
}
