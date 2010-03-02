package lombok.ast.syntaxChecks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.ast.AnnotationDeclaration;
import lombok.ast.Block;
import lombok.ast.ClassDeclaration;
import lombok.ast.CompilationUnit;
import lombok.ast.EnumDeclaration;
import lombok.ast.For;
import lombok.ast.ForEach;
import lombok.ast.InterfaceDeclaration;
import lombok.ast.KeywordModifier;
import lombok.ast.MethodDeclaration;
import lombok.ast.Modifiers;
import lombok.ast.Node;
import lombok.ast.SyntaxProblem;
import lombok.ast.TypeDeclaration;
import lombok.ast.VariableDeclaration;
import lombok.ast.VariableDefinition;
import lombok.ast.template.SyntaxCheck;

@SyntaxCheck
public class KeywordChecks {
	private final List<SyntaxProblem> problems;
	
	public KeywordChecks(List<SyntaxProblem> problems) {
		this.problems = problems;
	}
	
	public void duplicateKeywordModifierCheck(Modifiers modifiers) {
		List<String> keywords = new ArrayList<String>();
		
		for (Node n : modifiers.keywords().getRawContents()) {
			if (n instanceof KeywordModifier) {
				String k = ((KeywordModifier)n).getName();
				if (k != null && !k.isEmpty() ) {
					if (keywords.contains(k)) {
						problems.add(new SyntaxProblem(n, "Duplicate modifier: " + k));
					} else {
						keywords.add(k);
					}
				}
			}
		}
	}
	
	private static final int K_PRIVATE      = 0x0001;
	private static final int K_PROTECTED    = 0x0002;
	private static final int K_PUBLIC       = 0x0004;
	private static final int K_FINAL        = 0x0008;
	private static final int K_NATIVE       = 0x0010;
	private static final int K_STRICTFP     = 0x0020;
	private static final int K_SYNCHRONIZED = 0x0040;
	private static final int K_ABSTRACT     = 0x0080;
	private static final int K_STATIC       = 0x0100;
	private static final int K_TRANSIENT    = 0x0200;
	private static final int K_VOLATILE     = 0x0400;
	private static final Map<String, Integer> TO_FLAG_MAP;
	static {
		TO_FLAG_MAP = new HashMap<String, Integer>();
		TO_FLAG_MAP.put("private", K_PRIVATE);
		TO_FLAG_MAP.put("protected", K_PROTECTED);
		TO_FLAG_MAP.put("public", K_PUBLIC);
		TO_FLAG_MAP.put("final", K_FINAL);
		TO_FLAG_MAP.put("native", K_NATIVE);
		TO_FLAG_MAP.put("strictfp", K_STRICTFP);
		TO_FLAG_MAP.put("synchronized", K_SYNCHRONIZED);
		TO_FLAG_MAP.put("abstract", K_ABSTRACT);
		TO_FLAG_MAP.put("transient", K_TRANSIENT);
		TO_FLAG_MAP.put("volatile", K_VOLATILE);
	}
	
	private static final int[] METHOD_MODIFIERS_EXCLUSIVITY = {
		K_PRIVATE   | K_PROTECTED,
		K_PRIVATE   | K_PUBLIC,
		K_PROTECTED | K_PUBLIC,
		K_NATIVE    | K_STRICTFP,
		K_ABSTRACT  | K_FINAL,
		K_ABSTRACT  | K_NATIVE,
		K_ABSTRACT  | K_STATIC,
		K_ABSTRACT  | K_STRICTFP,
		K_ABSTRACT  | K_SYNCHRONIZED,
	};
	
	private static final int METHOD_MODIFIERS_LEGAL =
		K_PRIVATE | K_PROTECTED | K_PUBLIC | K_ABSTRACT | K_FINAL | K_NATIVE |
		K_STATIC | K_STRICTFP | K_SYNCHRONIZED
	;
	
	private static final int[] FIELD_MODIFIERS_EXCLUSIVITY = {
		K_PRIVATE | K_PROTECTED,
		K_PRIVATE | K_PUBLIC,
		K_PROTECTED | K_PUBLIC,
	};
	
	private static final int FIELD_MODIFIERS_LEGAL =
		K_PRIVATE | K_PROTECTED | K_PUBLIC | K_STATIC | K_FINAL | K_TRANSIENT | K_VOLATILE;
	
	private static final int[] TYPE_MODIFIERS_EXCLUSIVITY = {
		K_PRIVATE | K_PROTECTED,
		K_PRIVATE | K_PUBLIC,
		K_PROTECTED | K_PUBLIC,
		K_FINAL | K_ABSTRACT,
	};
	
	private static final int TYPE_MODIFIERS_LEGAL =
		K_PRIVATE | K_PROTECTED | K_PUBLIC | K_STATIC | K_FINAL | K_ABSTRACT | K_STRICTFP;
	
	public void methodModifiersCheck(MethodDeclaration md) {
		modifiersCheck(md.getRawModifiers(),
				METHOD_MODIFIERS_EXCLUSIVITY, METHOD_MODIFIERS_LEGAL, "method declarations");
		checkStaticChain(md.getRawModifiers());
	}
	
	public void fieldModifiersCheck(VariableDeclaration vd) {
		if (!(vd.getParent() instanceof TypeDeclaration)) return;
		Node rawDefinition = vd.getRawDefinition();
		if (rawDefinition instanceof VariableDefinition) {
			Node rawModifiers = ((VariableDefinition)rawDefinition).getRawModifiers();
			modifiersCheck(rawModifiers, FIELD_MODIFIERS_EXCLUSIVITY, FIELD_MODIFIERS_LEGAL, "field declarations");
			checkStaticChain(rawModifiers);
		}
	}
	
	public void localVariableModifiersCheck(VariableDefinition vd) {
		boolean applies = vd.getParent() instanceof VariableDeclaration && vd.getParent().getParent() instanceof Block;
		if (!applies) applies = vd.getParent() instanceof ForEach && ((ForEach)vd.getParent()).getRawVariable() == vd;
		if (!applies) applies = vd.getParent() instanceof For && ((For)vd.getParent()).inits().contains(vd);
		if (!applies) return;
		
		modifiersCheck(vd.getRawModifiers(), new int[0], K_FINAL, "local variable declarations");
	}
	
	public void classDeclarationModifiersCheck(ClassDeclaration cd) {
		typeModifiersCheck(cd, "class declarations");
	}
	
	public void interfaceDeclarationModifiersCheck(InterfaceDeclaration id) {
		int flags = typeModifiersCheck(id, "interface declarations");
		generateNotAllowedKeywordError(id.getRawModifiers(), flags, K_FINAL, "final", "Interfaces cannot be final");
	}
	
	public void annotationDeclarationModifiersCheck(AnnotationDeclaration ad) {
		int flags = typeModifiersCheck(ad, "annotation declarations");
		generateNotAllowedKeywordError(ad.getRawModifiers(), flags, K_FINAL, "final", "Annotations cannot be final");
	}
	
	public void enumDeclarationModifiersCheck(EnumDeclaration ad) {
		int flags = typeModifiersCheck(ad, "enum declarations");
		generateNotAllowedKeywordError(ad.getRawModifiers(), flags, K_FINAL, "final", "Enums cannot be marked final");
	}
	
	private int typeModifiersCheck(TypeDeclaration td, String desc) {
		int flags = modifiersCheck(td.getRawModifiers(),
				TYPE_MODIFIERS_EXCLUSIVITY, TYPE_MODIFIERS_LEGAL, desc);
		boolean staticWarningEmitted = false;
		if (td.getParent() instanceof CompilationUnit) {
			generateNotAllowedKeywordError(td.getRawModifiers(), flags, K_PRIVATE, "private", "Top-level types cannot be private.");
			staticWarningEmitted |= generateNotAllowedKeywordError(td.getRawModifiers(), flags, K_STATIC, "static", "Top-level types cannot be static.");
			
			if ((flags & (K_PRIVATE | K_PROTECTED | K_PUBLIC)) == 0) {
				problems.add(new SyntaxProblem(td, "Top-level types cannot be package private."));
			}
		} else if (td.getParent() instanceof Block) {
			generateNotAllowedKeywordError(td.getRawModifiers(), flags, K_PRIVATE, "private", "Method-local types cannot be private.");
			generateNotAllowedKeywordError(td.getRawModifiers(), flags, K_PROTECTED, "protected", "Method-local types cannot be protected.");
			generateNotAllowedKeywordError(td.getRawModifiers(), flags, K_PUBLIC, "public", "Method-local types cannot be public.");
			staticWarningEmitted |= generateNotAllowedKeywordError(td.getRawModifiers(), flags, K_STATIC, "static", "Method-local types cannot be static.");
		}
		
		if (!staticWarningEmitted) checkStaticChain(td.getRawModifiers());
		
		return flags;
	}
	
	private void checkStaticChain(Node rawModifiers) {
		if (!(rawModifiers instanceof Modifiers)) return;
		if (!((Modifiers)rawModifiers).isStatic()) return;
		
		Node p = rawModifiers.getParent();
		while (p != null) {
			if (p instanceof CompilationUnit) return;
			if (p instanceof TypeDeclaration) {
				Node pMods = ((TypeDeclaration)p).getRawModifiers();
				if (pMods instanceof Modifiers && !((Modifiers)pMods).isStatic()) {
					problems.add(new SyntaxProblem(rawModifiers.getParent(),
							"This declaration is (effectively) static; static declarations or only legal in top-level and static declarations."));
				}
			}
			return;
		}
	}
	
	private int modifiersCheck(Node rawModifiers, int[] exclusivity, int legality, String desc) {
		if (!(rawModifiers instanceof Modifiers)) return 0;
		int flags = 0;
		for (Node n : ((Modifiers)rawModifiers).keywords().getRawContents()) {
			if (n instanceof KeywordModifier) {
				String k = ((KeywordModifier)n).getName();
				if (k == null || k.isEmpty()) continue;
				if (!TO_FLAG_MAP.containsKey(k)) {
					problems.add(new SyntaxProblem(n, "Unknown modifier: " + k));
					continue;
				}
				
				int flag = TO_FLAG_MAP.get(k);
				if ((legality & flag) == 0) {
					problems.add(new SyntaxProblem(n, "Modifier not allowed on " + desc + ": " + k));
					continue;
				}
				
				flags |= flag;
			}
		}
		
		for (int exclusive : exclusivity) {
			if ((flags & exclusive) == exclusive) {
				generateExclusivityError(exclusive, rawModifiers);
			}
		}
		
		return flags;
	}
	
	private boolean generateNotAllowedKeywordError(Node rawModifiers, int flags, int flag, String keyword, String error) {
		if ((flags & flag) == 0) return false;
		
		if (!(rawModifiers instanceof Modifiers)) return false;
		
		for (Node n : ((Modifiers)rawModifiers).keywords().getRawContents()) {
			if (n instanceof KeywordModifier) {
				if (keyword.equals(((KeywordModifier)n).getName())) {
					problems.add(new SyntaxProblem(n, error));
					return true;
				}
			}
		}
		
		return false;
	}
	
	private void generateExclusivityError(int exclusive, Node rawModifiers) {
		if (!(rawModifiers instanceof Modifiers)) return;
		
		String hit = null;
		
		for (Node n : ((Modifiers)rawModifiers).keywords().getRawContents()) {
			if (n instanceof KeywordModifier) {
				String k = ((KeywordModifier)n).getName();
				if (!TO_FLAG_MAP.containsKey(k)) continue;
				int f = TO_FLAG_MAP.get(k);
				if ((f & exclusive) == 0) continue;
				
				if (hit == null) {
					hit = k;
					continue;
				}
				
				problems.add(new SyntaxProblem(n, String.format(
						"Modifier %s cannot be used together with %s here", k, hit)));
			}
		}
	}
}