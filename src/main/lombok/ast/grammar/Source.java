/*
 * Copyright © 2010 Reinier Zwitserloot and Roel Spilker.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.ast.grammar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import lombok.Getter;
import lombok.ast.Comment;
import lombok.ast.ForwardingAstVisitor;
import lombok.ast.JavadocContainer;
import lombok.ast.Node;
import lombok.ast.Position;

import org.parboiled.support.ParseError;
import org.parboiled.support.ParsingResult;

public class Source {
	@Getter private final String name;
	@Getter private final String rawInput;
	private List<Node> nodes = new ArrayList<Node>();
	private List<ParseProblem> problems = new ArrayList<ParseProblem>();
	private List<Comment> comments = new ArrayList<Comment>();
	private boolean parsed;
	
	private TreeMap<Integer, Integer> positionDeltas = new TreeMap<Integer, Integer>();
	private String preprocessed;
	
	public Source(String rawInput, String name) {
		this.rawInput = rawInput;
		this.name = name;
	}
	
	public List<Node> getNodes() {
		if (!parsed) throw new IllegalStateException("Code hasn't been parsed yet.");
		return nodes;
	}
	
	public List<ParseProblem> getProblems() {
		return problems;
	}
	
	public void parseCompilationUnit() {
		preProcess();
		ParserGroup group = new ParserGroup(this);
		postProcess(group.structures.parse(group.structures.compilationUnitEoi(), preprocessed));
	}
	
	private void postProcess(ParsingResult<Node> parsingResult) {
		nodes.add(parsingResult.parseTreeRoot.getValue());
		for (ParseError error : parsingResult.parseErrors) {
			problems.add(new ParseProblem(new Position(mapPosition(error.getErrorStart().index), mapPosition(error.getErrorEnd().index)), error.getErrorMessage()));
		}
		gatherComments(parsingResult.parseTreeRoot);
		comments = Collections.unmodifiableList(comments);
		nodes = Collections.unmodifiableList(nodes);
		problems = Collections.unmodifiableList(problems);
		
		//TODO Write test case with javadoc intermixed with empty declares.
		//TODO test javadoc on a package declaration.
		//TODO javadoc in between keywords.
		
		associateJavadoc(comments, nodes);
		parsed = true;
	}
	
	private void associateJavadoc(List<Comment> comments, List<Node> nodes) {
		final TreeMap<Integer, Node> startPosMap = new TreeMap<Integer, Node>();
		for (Node node : nodes) node.accept(new ForwardingAstVisitor() {
			@Override public boolean visitNode(Node node) {
				if (node.isGenerated()) return false;
				int startPos = node.getPosition().getStart();
				Node current = startPosMap.get(startPos);
				if (current == null || !(current instanceof JavadocContainer)) {
					startPosMap.put(startPos, node);
				}
				
				return false;
			}
		});
		
		for (Comment comment : comments) {
			if (!comment.isJavadoc()) continue;
			Map<Integer, Node> tailMap = startPosMap.tailMap(comment.getPosition().getEnd());
			if (tailMap.isEmpty()) continue;
			Node assoc = tailMap.values().iterator().next();
			if (!(assoc instanceof JavadocContainer)) continue;
			JavadocContainer jc = (JavadocContainer) assoc;
			if (jc.getRawJavadoc() != null) {
				if (jc.getRawJavadoc().getPosition().getEnd() >= comment.getPosition().getEnd()) continue;
			}
			jc.setRawJavadoc(comment);
		}
	}
	
	private void gatherComments(org.parboiled.Node<Node> parsed) {
		if (parsed.getValue() instanceof Comment) {
			comments.add((Comment) parsed.getValue());
			return;
		}
		
		for (org.parboiled.Node<Node> child : parsed.getChildren()) gatherComments(child);
	}
	
	private void setPositionDelta(int position, int delta) {
		Integer i = positionDeltas.get(position);
		if (i == null) i = 0;
		positionDeltas.put(position, i + delta);
	}
	
	int mapPosition(int position) {
		int out = position;
		for (int delta : positionDeltas.headMap(position, true).values()) {
			out += delta;
		}
		return out;
	}
	
	int mapPositionRtrim(int pos) {
		char c;
		
		pos = Math.min(pos, preprocessed.length());
		
		do {
			if (pos == 0) return 0;
			c = preprocessed.charAt(--pos);
		} while (Character.isWhitespace(c));
		
		return mapPosition(pos+1);
	}
	
	private String preProcess() {
		preprocessed = rawInput;
		applyBackslashU();
//		applyBraceMatching();
		return preprocessed;
	}
	
	/**
	 * @see http://java.sun.com/docs/books/jls/third_edition/html/lexical.html#3.3
	 */
	private void applyBackslashU() {
		StringBuilder buffer = new StringBuilder();
		StringBuilder out = new StringBuilder();
		
		int state = 0;
		int idx = 0;
		for (char c : preprocessed.toCharArray()) {
			idx++;
			switch (state) {
			case 0:	//normal mode. Anything that isn't a backslash is not interesting.
				if (c != '\\') {
					out.append(c);
					break;
				}
				
				state = 1;
				break;
			case 1:	//Last character read is an (uneven amount of) backslash.
				if (c != 'u') {
					out.append('\\');
					out.append(c);
					state = 0;
				} else {
					buffer.setLength(0);
					buffer.append("\\u");
					state = 2;
				}
				break;
			default:
				//Gobbling hex digits. state-2 is our current position. We want 4.
				buffer.append(c);
				if (c == 'u') {
					//JLS Puzzler: backslash-u-u-u-u-u-u-u-u-u-4hexdigits means the same thing as just 1 u.
					//So, we just keep going as if nothing changed.
				} else if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
					state++;
					if (state == 6) {
						//We've got our 4 hex digits.
						out.append((char)Integer.parseInt(buffer.substring(buffer.length()-4), 0x10));
						buffer.setLength(0);
						//We don't have to check if this char is a backslash and set state to 1; JLS says backslash-u is not recursively applied.
						state = 0;
						setPositionDelta(idx, 1-buffer.length());	//buffer goes away, but in its place, 1 character.
					}
				} else {
					//Invalid unicode escape.
					problems.add(new ParseProblem(new Position(idx-buffer.length(), idx), "Invalid backslash-u escape: \\u is supposed to be followed by 4 hex digits."));
					out.append(buffer.toString());
					state = 0;
				}
				break;
			}
		}
		
		preprocessed = out.toString();
	}
}
