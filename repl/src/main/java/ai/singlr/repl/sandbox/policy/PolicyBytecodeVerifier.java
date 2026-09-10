/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

import java.lang.classfile.BootstrapMethodEntry;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.ConstantDynamicEntry;
import java.lang.classfile.constantpool.ConstantValueEntry;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.constantpool.MethodTypeEntry;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link BytecodeVerifier} implementation that scans a class's compiled bytes against a {@link
 * SandboxPolicy} using the JDK Classfile API (JEP 484, finalised in JDK 24). Rejects the first
 * denied operation it finds by throwing {@link SandboxPolicyException}.
 *
 * <p><strong>Instruction families scanned.</strong> Every method's code stream is walked for the
 * instruction families that can name a policy-relevant API:
 *
 * <ul>
 *   <li>{@link InvokeInstruction} — {@code INVOKEVIRTUAL} / {@code INVOKESPECIAL} / {@code
 *       INVOKESTATIC} / {@code INVOKEINTERFACE}. Catches method calls.
 *   <li>{@link NewObjectInstruction} — {@code NEW}. Catches instantiation before the {@code <init>}
 *       even runs.
 *   <li>{@link FieldInstruction} — {@code GET*} / {@code PUT*}. Catches static-field access that
 *       names denied classes (e.g. {@code System.out} when {@code java.lang.System} is denied).
 *   <li>{@link ConstantInstruction.LoadConstantInstruction} — {@code LDC} of a class, method handle
 *       or dynamic constant. Class literals like {@code ProcessBuilder.class} (array types are
 *       peeled to their element class) are checked as class mentions; method-handle constants are
 *       checked as owner/member references; dynamic constants are walked as described below.
 *   <li>{@link InvokeDynamicInstruction} — {@code INVOKEDYNAMIC}. The bootstrap method and every
 *       bootstrap argument are walked as described below.
 * </ul>
 *
 * <p><strong>Indirect references.</strong> A method handle in a bootstrap argument is a capability
 * exactly like a direct call: {@code FileReader::new} compiles to an {@code INVOKEDYNAMIC} whose
 * {@code LambdaMetafactory} argument list carries a {@code REF_newInvokeSpecial
 * java/io/FileReader.<init>} handle, and no ordinary invoke instruction ever names the constructor.
 * The verifier therefore applies the same owner/member policy to every reference reachable through
 * {@code INVOKEDYNAMIC} bootstraps, method-handle constants and dynamic constants, including nested
 * bootstrap arguments. Language bootstraps whose dispatch is platform code — {@code
 * LambdaMetafactory}, {@code StringConcatFactory}, {@code ObjectMethods}, {@code SwitchBootstraps}
 * and the {@code ConstantBootstraps} materialisers — are trusted as dispatchers so lambdas,
 * records, string concatenation and pattern switches keep working under every policy, but their
 * user-supplied arguments are never trusted. Any other bootstrap is checked like a direct call.
 * Method-type signatures are not policy-relevant for direct invokes and stay unchecked here too.
 *
 * <p>Traversal is over constant-pool entries rather than nominal descriptors (whose construction
 * eagerly recurses through nested arguments), is bounded to {@value #MAX_CONSTANT_DEPTH} nested
 * dynamic constants, memoizes the greatest checked depth per constant within each class, rejects a
 * dynamic constant that references itself, and rejects any classfile the Classfile API cannot
 * parse. Each of those surfaces as a {@link SandboxPolicyException} with a rule label ({@code
 * dynamicConstantDepth}, {@code dynamicConstantCycle}, {@code malformedClassfile}) so malformed
 * input fails closed instead of escaping the scan.
 *
 * <p><strong>Rule order.</strong> Explicit {@link SandboxPolicy#deniedClasses()} and {@link
 * SandboxPolicy#deniedPackages()} match before the categorical flags ({@link
 * SandboxPolicy#denyReflection()}, {@link SandboxPolicy#denyNativeAccess()}, {@link
 * SandboxPolicy#denyDynamicClassDefinition()}). This means the exception's {@code rule} label names
 * the most specific user-configured rule when overlap occurs.
 *
 * <p><strong>Static-receiver-type limitation.</strong> {@code INVOKEVIRTUAL} and {@code
 * INVOKEINTERFACE} carry the receiver's <em>static</em> compile-time type as the instruction owner,
 * not the runtime class hierarchy. So a deny on {@code java.lang.ClassLoader.defineClass} fires for
 * {@code ((ClassLoader) cl).defineClass(...)} (owner resolves to {@code java/lang/ClassLoader}),
 * but does <em>not</em> fire for {@code urlClassLoader.defineClass(...)} where the variable's
 * static type is {@code URLClassLoader} — javac emits {@code INVOKEVIRTUAL
 * URLClassLoader.defineClass} and the bytecode owner string is the narrowed subclass.
 *
 * <p>This is a real conceptual limit, not a bug: closing it requires walking the JDK class
 * hierarchy at scan time (loadable-class introspection, not cheap, and brittle across JDK
 * versions). The practical mitigations in this codebase:
 *
 * <ul>
 *   <li>The realistic dynamic-class-load entry point is {@code MethodHandles.Lookup.defineClass},
 *       which IS caught by name on its own owner. {@code ClassLoader.defineClass} is {@code
 *       protected}, so snippets can't usually reach it via a {@code ClassLoader} reference.
 *   <li>{@link SandboxPolicy#denyReflection() denyReflection} denies entire {@code
 *       java/lang/reflect/*} and {@code java/lang/invoke/*} packages, closing most class-load
 *       primitives regardless of receiver typing.
 *   <li>The OS-level isolation boundary (Incus / namespaces / per-UID separation) remains the only
 *       authoritative perimeter — in-JVM policy is defense-in-depth.
 * </ul>
 *
 * <p>If a deployer needs to ban a method on a non-final class whose subclasses they cannot
 * enumerate, the right move is to deny the package or use a curated allow-list, not to rely on
 * per-method denial.
 */
public final class PolicyBytecodeVerifier implements BytecodeVerifier {

  private static final String REFLECTION_PKG = "java/lang/reflect/";
  private static final String INVOKE_PKG = "java/lang/invoke/";
  private static final String FOREIGN_PKG = "java/lang/foreign/";
  private static final String NIO_FILE_PKG = "java/nio/file/";
  private static final String CLASS_OWNER = "java/lang/Class";
  private static final String FILE_OWNER = "java/io/File";

  /**
   * Internal-form prefixes considered "JDK-scoped" for the allow-list rule. An owner under one of
   * these is subject to the allow-list check; any other owner is treated as snippet-own / user code
   * and bypasses allow-list (deny rules still apply).
   */
  private static final List<String> JDK_PREFIXES =
      List.of("java/", "javax/", "jdk/", "sun/", "com/sun/");

  /** Maximum nesting of dynamic constants the walker follows before failing closed. */
  static final int MAX_CONSTANT_DEPTH = 32;

  /**
   * Bootstrap methods the JVM dispatches on the language's behalf. Their dispatch is trusted so
   * lambdas, method references, string concatenation, record methods, pattern switches and
   * compiler-emitted dynamic constants stay usable under every policy; their arguments are checked
   * like any other reference. A bootstrap outside this set is checked as a direct static call.
   */
  private static final Set<String> TRUSTED_BOOTSTRAPS =
      Set.of(
          "java/lang/invoke/LambdaMetafactory.metafactory",
          "java/lang/invoke/LambdaMetafactory.altMetafactory",
          "java/lang/invoke/StringConcatFactory.makeConcat",
          "java/lang/invoke/StringConcatFactory.makeConcatWithConstants",
          "java/lang/runtime/ObjectMethods.bootstrap",
          "java/lang/runtime/SwitchBootstraps.typeSwitch",
          "java/lang/runtime/SwitchBootstraps.enumSwitch",
          "java/lang/invoke/ConstantBootstraps.nullConstant",
          "java/lang/invoke/ConstantBootstraps.primitiveClass",
          "java/lang/invoke/ConstantBootstraps.enumConstant",
          "java/lang/invoke/ConstantBootstraps.getStaticFinal",
          "java/lang/invoke/ConstantBootstraps.invoke",
          "java/lang/invoke/ConstantBootstraps.explicitCast",
          "java/lang/invoke/ConstantBootstraps.fieldVarHandle",
          "java/lang/invoke/ConstantBootstraps.staticFieldVarHandle",
          "java/lang/invoke/ConstantBootstraps.arrayVarHandle");

  private static final Set<String> NATIVE_ACCESS_MEMBERS =
      Set.of(
          "java/lang/System.loadLibrary",
          "java/lang/System.load",
          "java/lang/Runtime.loadLibrary",
          "java/lang/Runtime.load");

  private static final Set<String> DYN_CLASS_DEF_MEMBERS =
      Set.of(
          "java/lang/invoke/MethodHandles$Lookup.defineClass",
          "java/lang/invoke/MethodHandles$Lookup.defineHiddenClass",
          "java/lang/invoke/MethodHandles$Lookup.defineHiddenClassWithClassData",
          "java/lang/ClassLoader.defineClass");

  /**
   * Classes whose every member touches the host filesystem when the snippet invokes them. Includes
   * the {@code java.io.File*Stream} / {@code Reader} / {@code Writer} family, {@code
   * RandomAccessFile}, {@code FileDescriptor} (the FD container — exposes raw native FDs that
   * bypass the IO chokepoint), and the file-backed NIO channels. The {@code java.nio.file} package
   * is denied via a prefix check below rather than enumerated here because every member of that
   * package touches the filesystem and the package gains classes across JDK versions.
   */
  private static final Set<String> FS_DENIED_OWNERS =
      Set.of(
          "java/io/FileInputStream",
          "java/io/FileOutputStream",
          "java/io/FileReader",
          "java/io/FileWriter",
          "java/io/RandomAccessFile",
          "java/io/FileDescriptor",
          "java/nio/channels/FileChannel",
          "java/nio/channels/AsynchronousFileChannel");

  /**
   * Members on {@code java.io.File} that reach the filesystem (read directory contents, query
   * existence / size / timestamps, create / delete / rename, query free space). Listed explicitly
   * so {@code new File(path)} and the purely-string members ({@code getName}, {@code getPath},
   * {@code getAbsolutePath}, {@code toString}, ...) stay callable — those don't touch the
   * filesystem and they're how snippets manipulate path strings.
   *
   * <p>{@code toPath()} appears here because the returned {@link java.nio.file.Path} lives in
   * {@code java.nio.file}, which the prefix check denies — but denying at the {@code File} boundary
   * too gives a clearer rule label and short-circuits the call before the {@code Path} object is
   * materialised.
   */
  private static final Set<String> FILE_UNSAFE_MEMBERS =
      Set.of(
          "list",
          "listFiles",
          "listRoots",
          "exists",
          "length",
          "lastModified",
          "isDirectory",
          "isFile",
          "isHidden",
          "canRead",
          "canWrite",
          "canExecute",
          "getCanonicalPath",
          "getCanonicalFile",
          "getFreeSpace",
          "getTotalSpace",
          "getUsableSpace",
          "createNewFile",
          "delete",
          "deleteOnExit",
          "mkdir",
          "mkdirs",
          "renameTo",
          "setReadable",
          "setWritable",
          "setExecutable",
          "setReadOnly",
          "setLastModified",
          "toPath");

  private final SandboxPolicy policy;
  private final Set<String> allowedPackagesInternal;
  private final Set<String> deniedClassesInternal;
  private final Set<String> deniedPackagesInternal;

  /**
   * Construct a verifier for {@code policy}. The policy's deny / allow lists are eagerly converted
   * to internal form ({@code java/lang/ProcessBuilder} rather than {@code
   * java.lang.ProcessBuilder}) so every per-instruction check is a single set lookup.
   */
  public PolicyBytecodeVerifier(SandboxPolicy policy) {
    if (policy == null) {
      throw new IllegalArgumentException("policy must not be null");
    }
    this.policy = policy;
    this.allowedPackagesInternal =
        policy.allowedPackages().stream()
            .map(s -> s.replace('.', '/') + "/")
            .collect(Collectors.toUnmodifiableSet());
    this.deniedClassesInternal =
        policy.deniedClasses().stream()
            .map(s -> s.replace('.', '/'))
            .collect(Collectors.toUnmodifiableSet());
    this.deniedPackagesInternal =
        policy.deniedPackages().stream()
            .map(s -> s.replace('.', '/') + "/")
            .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public void verify(String internalName, byte[] bytecodes) {
    try {
      var classModel = ClassFile.of().parse(bytecodes);
      var checkedDepth = new HashMap<Integer, Integer>();
      for (var method : classModel.methods()) {
        method.code().ifPresent(code -> checkCode(code, checkedDepth));
      }
    } catch (SandboxPolicyException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new SandboxPolicyException(internalName, null, "malformedClassfile");
    }
  }

  private void checkCode(CodeModel code, Map<Integer, Integer> checkedDepth) {
    for (var element : code.elementList()) {
      switch (element) {
        case InvokeInstruction ins ->
            checkOwnerMember(ownerOf(ins.owner()), ins.name().stringValue());
        case NewObjectInstruction ins -> checkOwnerMember(ownerOf(ins.className()), "<init>");
        case FieldInstruction ins ->
            checkOwnerMember(ownerOf(ins.owner()), ins.name().stringValue());
        case ConstantInstruction.LoadConstantInstruction ins ->
            checkConstant(ins.constantEntry(), new HashSet<>(), checkedDepth);
        case InvokeDynamicInstruction ins ->
            checkBootstrap(ins.invokedynamic().bootstrap(), new HashSet<>(), checkedDepth);
        default -> {}
      }
    }
  }

  private void checkBootstrap(
      BootstrapMethodEntry bootstrap, Set<Integer> path, Map<Integer, Integer> checkedDepth) {
    var handle = bootstrap.bootstrapMethod();
    if (!isTrustedBootstrap(handle)) {
      checkHandle(handle);
    }
    for (var argument : bootstrap.arguments()) {
      checkConstant(argument, path, checkedDepth);
    }
  }

  private void checkConstant(
      LoadableConstantEntry entry, Set<Integer> path, Map<Integer, Integer> checkedDepth) {
    switch (entry) {
      case ClassEntry c -> checkOwnerMember(ownerOf(c), null);
      case MethodHandleEntry h -> checkHandle(h);
      case ConstantDynamicEntry d -> checkDynamicConstant(d, path, checkedDepth);
      case MethodTypeEntry t -> {}
      case ConstantValueEntry v -> {}
    }
  }

  private void checkDynamicConstant(
      ConstantDynamicEntry entry, Set<Integer> path, Map<Integer, Integer> checkedDepth) {
    var depth = path.size();
    if (depth >= MAX_CONSTANT_DEPTH) {
      throw new SandboxPolicyException(null, null, "dynamicConstantDepth");
    }
    if (!path.add(entry.index())) {
      throw new SandboxPolicyException(null, null, "dynamicConstantCycle");
    }
    if (checkedDepth.getOrDefault(entry.index(), -1) < depth) {
      checkOwnerMember(ownerOf(entry.typeSymbol()), null);
      checkBootstrap(entry.bootstrap(), path, checkedDepth);
      checkedDepth.put(entry.index(), depth);
    }
    path.remove(entry.index());
  }

  private void checkHandle(MethodHandleEntry handle) {
    var reference = handle.reference();
    checkOwnerMember(ownerOf(reference.owner()), reference.name().stringValue());
  }

  private static boolean isTrustedBootstrap(MethodHandleEntry handle) {
    var reference = handle.reference();
    return handle.kind() == DirectMethodHandleDesc.Kind.STATIC.refKind
        && TRUSTED_BOOTSTRAPS.contains(
            reference.owner().asInternalName() + "." + reference.name().stringValue());
  }

  private static String ownerOf(ClassEntry entry) {
    return ownerOf(entry.asSymbol());
  }

  /**
   * Internal-form name of the class an owner descriptor refers to, peeling array types down to
   * their element class so {@code ProcessBuilder[].class} is judged as {@code ProcessBuilder}.
   * Returns {@code null} for primitives and primitive arrays, which name no class at all.
   */
  private static String ownerOf(ClassDesc desc) {
    var element = desc;
    while (element.isArray()) {
      element = element.componentType();
    }
    if (!element.isClassOrInterface()) {
      return null;
    }
    var descriptor = element.descriptorString();
    return descriptor.substring(1, descriptor.length() - 1);
  }

  private void checkOwnerMember(String ownerInternal, String member) {
    if (ownerInternal == null) {
      return;
    }
    if (deniedClassesInternal.contains(ownerInternal)) {
      throw new SandboxPolicyException(
          ownerInternal, member, "deniedClasses:" + ownerInternal.replace('/', '.'));
    }
    for (var pkg : deniedPackagesInternal) {
      if (ownerInternal.startsWith(pkg)) {
        throw new SandboxPolicyException(
            ownerInternal,
            member,
            "deniedPackages:" + pkg.substring(0, pkg.length() - 1).replace('/', '.'));
      }
    }
    if (policy.denyReflection() && isReflection(ownerInternal, member)) {
      throw new SandboxPolicyException(ownerInternal, member, "denyReflection");
    }
    if (policy.denyNativeAccess() && isNativeAccess(ownerInternal, member)) {
      throw new SandboxPolicyException(ownerInternal, member, "denyNativeAccess");
    }
    if (policy.denyDynamicClassDefinition() && isDynamicClassDefinition(ownerInternal, member)) {
      throw new SandboxPolicyException(ownerInternal, member, "denyDynamicClassDefinition");
    }
    if (policy.denyFileSystemAccess() && isFileSystemAccess(ownerInternal, member)) {
      throw new SandboxPolicyException(ownerInternal, member, "denyFileSystemAccess");
    }
    if (!allowedPackagesInternal.isEmpty()
        && isJdkScoped(ownerInternal)
        && !isInAllowedPackage(ownerInternal)) {
      throw new SandboxPolicyException(ownerInternal, member, "allowedPackages-default-deny");
    }
  }

  /**
   * Allow-list rule. Treats owners under {@code java/}, {@code javax/}, {@code jdk/}, {@code sun/},
   * or {@code com/sun/} as JDK-scoped — subject to the allow-list. Everything else (snippet's own
   * {@code REPL.$JShell$N} wrappers, user-declared helper classes, third-party JARs on the
   * snippet's classpath) is considered user code and always allowed regardless of the allow-list.
   * Caller has already established that {@code allowedPackages} is non-empty.
   */
  private static boolean isJdkScoped(String owner) {
    for (var prefix : JDK_PREFIXES) {
      if (owner.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private boolean isInAllowedPackage(String owner) {
    for (var pkg : allowedPackagesInternal) {
      if (owner.startsWith(pkg)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reflection rule. Denies entire {@code java/lang/reflect/} and {@code java/lang/invoke/}
   * packages plus the reflective entry points on {@code java/lang/Class}. Non-reflective {@code
   * Class} methods ({@code getName}, {@code cast}, {@code isInstance}, ...) stay callable because
   * they're not paths to reflection.
   *
   * <p>Denied {@code Class} members:
   *
   * <ul>
   *   <li>{@code forName} — string-driven class lookup
   *   <li>{@code newInstance} — deprecated but still present reflective instantiation
   *   <li>{@code getMethod*}, {@code getField*}, {@code getConstructor*} — return {@code
   *       java.lang.reflect.*} objects
   *   <li>{@code getDeclared*} — same family
   *   <li>{@code getEnclosingMethod}, {@code getEnclosingConstructor} — return {@code Method} /
   *       {@code Constructor}; the values themselves can't be used without invoking on {@code
   *       java/lang/reflect/*} (also denied), but denying at the entry point gives a clearer rule
   *       label
   *   <li>{@code getRecordComponents} — returns {@code RecordComponent[]}, each carrying a {@code
   *       Method} accessor
   * </ul>
   */
  private static boolean isReflection(String owner, String member) {
    if (owner.startsWith(REFLECTION_PKG) || owner.startsWith(INVOKE_PKG)) {
      return true;
    }
    if (owner.equals(CLASS_OWNER) && member != null) {
      return member.equals("forName")
          || member.equals("newInstance")
          || member.equals("getRecordComponents")
          || member.equals("getEnclosingMethod")
          || member.equals("getEnclosingConstructor")
          || member.startsWith("getMethod")
          || member.startsWith("getField")
          || member.startsWith("getConstructor")
          || member.startsWith("getDeclared");
    }
    return false;
  }

  /**
   * Native-access rule. Denies the Panama FFI surface ({@code java.lang.foreign}) and the {@code
   * loadLibrary} / {@code load} methods on {@code System} and {@code Runtime}, which are the
   * pre-Panama escape hatch into native code.
   */
  private static boolean isNativeAccess(String owner, String member) {
    if (owner.startsWith(FOREIGN_PKG)) {
      return true;
    }
    return member != null && NATIVE_ACCESS_MEMBERS.contains(owner + "." + member);
  }

  /**
   * Dynamic-class-definition rule. Denies the three call sites that materialise a {@code Class<?>}
   * from a {@code byte[]}: {@code MethodHandles.Lookup.defineClass}, {@code defineHiddenClass}
   * (with and without class data), and {@code ClassLoader.defineClass}. Without this rule, a
   * snippet that obtains a {@code Lookup} could define a class containing instructions the verifier
   * never saw.
   */
  private static boolean isDynamicClassDefinition(String owner, String member) {
    return member != null && DYN_CLASS_DEF_MEMBERS.contains(owner + "." + member);
  }

  /**
   * Filesystem-access rule. Denies three categories of snippet-callable filesystem reach:
   *
   * <ul>
   *   <li>Every member of the {@code java.nio.file} package (prefix match). Catches {@code
   *       Files.readAllBytes}, {@code Files.list}, {@code Files.walk}, {@code Paths.get}, {@code
   *       FileSystems.getDefault}, {@code WatchService}, and every NIO entry point that touches the
   *       filesystem.
   *   <li>Every member of the file-IO opener classes — {@code File*Stream}, {@code FileReader},
   *       {@code FileWriter}, {@code RandomAccessFile}, {@code FileDescriptor}, and the {@code
   *       FileChannel} / {@code AsynchronousFileChannel} NIO channels. The class is denied outright
   *       (every method), since opening a stream / channel is the only thing these classes exist
   *       for.
   *   <li>The filesystem-touching members of {@code java.io.File} (list, listFiles, exists, length,
   *       lastModified, isDirectory, canRead, createNewFile, delete, mkdir, renameTo, and the other
   *       queries / mutations enumerated in {@link #FILE_UNSAFE_MEMBERS}). The File class itself
   *       remains callable for path-string construction and the purely-string accessors so snippets
   *       can build / format / parse paths without touching the kernel.
   * </ul>
   */
  private static boolean isFileSystemAccess(String owner, String member) {
    if (owner.startsWith(NIO_FILE_PKG)) {
      return true;
    }
    if (FS_DENIED_OWNERS.contains(owner)) {
      return true;
    }
    return owner.equals(FILE_OWNER) && member != null && FILE_UNSAFE_MEMBERS.contains(member);
  }
}
