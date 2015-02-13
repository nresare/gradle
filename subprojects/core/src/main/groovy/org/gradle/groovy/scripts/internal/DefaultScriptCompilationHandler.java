/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.groovy.scripts.internal;

import com.google.common.collect.ImmutableList;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyCodeSource;
import groovy.lang.Script;
import groovyjarjarasm.asm.ClassWriter;
import org.apache.commons.lang.StringUtils;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.classgen.Verifier;
import org.codehaus.groovy.control.*;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.internal.initialization.ClassLoaderIds;
import org.gradle.api.internal.initialization.loadercache.ClassLoaderCache;
import org.gradle.groovy.scripts.ScriptCompilationException;
import org.gradle.groovy.scripts.ScriptSource;
import org.gradle.groovy.scripts.Transformer;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradle.messaging.serialize.Serializer;
import org.gradle.messaging.serialize.kryo.KryoBackedDecoder;
import org.gradle.messaging.serialize.kryo.KryoBackedEncoder;
import org.gradle.util.Clock;
import org.gradle.util.GFileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Field;
import java.security.CodeSource;
import java.util.List;

public class DefaultScriptCompilationHandler implements ScriptCompilationHandler {
    private Logger logger = LoggerFactory.getLogger(DefaultScriptCompilationHandler.class);
    private static final String EMPTY_SCRIPT_MARKER_FILE_NAME = "emptyScript.txt";
    private static final String IMPERATIVE_STATEMENTS_MARKER_FILE_NAME = "hasImperativeStatements.txt";
    private static final String METADATA_FILE_NAME = "metadata.bin";
    private final EmptyScriptGenerator emptyScriptGenerator;
    private final ClassLoaderCache classLoaderCache;

    public DefaultScriptCompilationHandler(EmptyScriptGenerator emptyScriptGenerator, ClassLoaderCache classLoaderCache) {
        this.emptyScriptGenerator = emptyScriptGenerator;
        this.classLoaderCache = classLoaderCache;
    }

    @Override
    public void compileToDir(ScriptSource source, ClassLoader classLoader, File classesDir, File metadataDir, MetadataExtractingTransformer<?> extractingTransformer, String classpathClosureName,
                             Class<? extends Script> scriptBaseClass, Action<? super ClassNode> verifier) {
        Clock clock = new Clock();
        GFileUtils.deleteDirectory(classesDir);
        GFileUtils.mkdirs(classesDir);
        CompilerConfiguration configuration = createBaseCompilerConfiguration(scriptBaseClass);
        configuration.setTargetDirectory(classesDir);
        try {
            compileScript(source, classLoader, configuration, classesDir, metadataDir, extractingTransformer, verifier, classpathClosureName);
        } catch (GradleException e) {
            GFileUtils.deleteDirectory(classesDir);
            throw e;
        }

        logger.debug("Timing: Writing script to cache at {} took: {}", classesDir.getAbsolutePath(),
                clock.getTime());
    }

    private void compileScript(final ScriptSource source, ClassLoader classLoader, CompilerConfiguration configuration, File classesDir, File metadataDir,
                               final MetadataExtractingTransformer<?> extractingTransformer, final Action<? super ClassNode> customVerifier, String classpathClosureName) {
        final Transformer transformer = extractingTransformer != null ? extractingTransformer.getTransformer() : null;
        logger.info("Compiling {} using {}.", source.getDisplayName(), transformer != null ? transformer.getClass().getSimpleName() : "no transformer");

        final EmptyScriptDetector emptyScriptDetector = new EmptyScriptDetector();
        final PackageStatementDetector packageDetector = new PackageStatementDetector();
        final ImperativeStatementDetector imperativeStatementDetector = new ImperativeStatementDetector(classpathClosureName);
        GroovyClassLoader groovyClassLoader = new GroovyClassLoader(classLoader, configuration, false) {
            @Override
            protected CompilationUnit createCompilationUnit(CompilerConfiguration compilerConfiguration,
                                                            CodeSource codeSource) {
                CompilationUnit compilationUnit = new CustomCompilationUnit(compilerConfiguration, codeSource, customVerifier, source, this);

                if (transformer != null) {
                    transformer.register(compilationUnit);
                }

                compilationUnit.addPhaseOperation(packageDetector, Phases.CANONICALIZATION);
                compilationUnit.addPhaseOperation(emptyScriptDetector, Phases.CANONICALIZATION);
                compilationUnit.addPhaseOperation(imperativeStatementDetector, Phases.CANONICALIZATION);
                return compilationUnit;
            }
        };
        String scriptText = source.getResource().getText();
        String scriptName = source.getClassName();
        GroovyCodeSource codeSource = new GroovyCodeSource(scriptText == null ? "" : scriptText, scriptName, "/groovy/script");
        try {
            groovyClassLoader.parseClass(codeSource, false);
        } catch (MultipleCompilationErrorsException e) {
            wrapCompilationFailure(source, e);
        } catch (CompilationFailedException e) {
            throw new GradleException(String.format("Could not compile %s.", source.getDisplayName()), e);
        }

        if (packageDetector.hasPackageStatement) {
            throw new UnsupportedOperationException(String.format("%s should not contain a package statement.",
                    StringUtils.capitalize(source.getDisplayName())));
        }
        if (emptyScriptDetector.isEmptyScript()) {
            GFileUtils.touch(new File(classesDir, EMPTY_SCRIPT_MARKER_FILE_NAME));
        }
        if (imperativeStatementDetector.hasImperativeStatements) {
            GFileUtils.touch(new File(classesDir, IMPERATIVE_STATEMENTS_MARKER_FILE_NAME));
        }
        serializeMetadata(source, extractingTransformer, metadataDir);
    }

    private <M> void serializeMetadata(ScriptSource scriptSource, MetadataExtractingTransformer<M> extractingTransformer, File metadataDir) {
        if (extractingTransformer == null || extractingTransformer.getMetadataSerializer() == null) {
            return;
        }
        GFileUtils.mkdirs(metadataDir);
        File metadataFile = new File(metadataDir, METADATA_FILE_NAME);
        FileOutputStream outputStream;
        try {
            outputStream = new FileOutputStream(metadataFile);
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException("Could not create or open build script metadata file " + metadataFile.getAbsolutePath(), e);
        }
        KryoBackedEncoder encoder = new KryoBackedEncoder(outputStream);
        Serializer<M> serializer = extractingTransformer.getMetadataSerializer();
        try {
            serializer.write(encoder, extractingTransformer.getExtractedMetadata());
        } catch (Exception e) {
            String transformerName = extractingTransformer.getTransformer().getClass().getName();
            throw new IllegalStateException(String.format("Failed to serialize script metadata extracted using %s for %s", transformerName, scriptSource.getDisplayName()), e);
        } finally {
            encoder.close();
        }

    }

    private void wrapCompilationFailure(ScriptSource source, MultipleCompilationErrorsException e) {
        // Fix the source file name displayed in the error messages
        for (Object message : e.getErrorCollector().getErrors()) {
            if (message instanceof SyntaxErrorMessage) {
                try {
                    SyntaxErrorMessage syntaxErrorMessage = (SyntaxErrorMessage) message;
                    Field sourceField = SyntaxErrorMessage.class.getDeclaredField("source");
                    sourceField.setAccessible(true);
                    SourceUnit sourceUnit = (SourceUnit) sourceField.get(syntaxErrorMessage);
                    Field nameField = SourceUnit.class.getDeclaredField("name");
                    nameField.setAccessible(true);
                    nameField.set(sourceUnit, source.getDisplayName());
                } catch (Exception failure) {
                    throw UncheckedException.throwAsUncheckedException(failure);
                }
            }
        }

        SyntaxException syntaxError = e.getErrorCollector().getSyntaxError(0);
        Integer lineNumber = syntaxError == null ? null : syntaxError.getLine();
        throw new ScriptCompilationException(String.format("Could not compile %s.", source.getDisplayName()), e, source,
                lineNumber);
    }

    private CompilerConfiguration createBaseCompilerConfiguration(Class<? extends Script> scriptBaseClass) {
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.setScriptBaseClass(scriptBaseClass.getName());
        return configuration;
    }

    public <T extends Script, M> CompiledScript<T, M> loadFromDir(final ScriptSource source, final ClassLoader classLoader, final File scriptCacheDir,
                                                                  File metadataCacheDir, MetadataExtractingTransformer<M> transformer, final Class<T> scriptBaseClass) {

        final boolean hasImperativeStatements = new File(scriptCacheDir, IMPERATIVE_STATEMENTS_MARKER_FILE_NAME).isFile();
        final M metadata = deserializeMetadata(source, transformer, metadataCacheDir);

        return new ClassCachingCompiledScript<T, M>(new CompiledScript<T, M>() {

            public boolean hasImperativeStatements() {
                return hasImperativeStatements;
            }

            @Override
            public Class<? extends T> loadClass() {
                if (new File(scriptCacheDir, EMPTY_SCRIPT_MARKER_FILE_NAME).isFile()) {
                    return emptyScriptGenerator.generate(scriptBaseClass);
                }

                try {
                    ClassLoader loader = classLoaderCache.get(ClassLoaderIds.buildScript(source.getFileName()), new DefaultClassPath(scriptCacheDir), classLoader, null);
                    return loader.loadClass(source.getClassName()).asSubclass(scriptBaseClass);
                } catch (Exception e) {
                    File expectedClassFile = new File(scriptCacheDir, source.getClassName() + ".class");
                    if (!expectedClassFile.exists()) {
                        throw new GradleException(String.format("Could not load compiled classes for %s from cache. Expected class file %s does not exist.", source.getDisplayName(), expectedClassFile.getAbsolutePath()), e);
                    }
                    throw new GradleException(String.format("Could not load compiled classes for %s from cache.", source.getDisplayName()), e);
                }
            }

            @Override
            public M getMetadata() {
                return metadata;
            }
        });
    }

    private <M> M deserializeMetadata(ScriptSource scriptSource, MetadataExtractingTransformer<M> extractingTransformer, File metadataCacheDir) {
        if (extractingTransformer == null || extractingTransformer.getMetadataSerializer() == null) {
            return null;
        }
        File metadataFile = new File(metadataCacheDir, METADATA_FILE_NAME);
        FileInputStream inputStream;
        try {
            inputStream = new FileInputStream(metadataFile);
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException("Could not open build script metadata file " + metadataFile.getAbsolutePath(), e);
        }
        KryoBackedDecoder decoder = new KryoBackedDecoder(inputStream);
        Serializer<M> serializer = extractingTransformer.getMetadataSerializer();
        try {
            return serializer.read(decoder);
        } catch (Exception e) {
            String transformerName = extractingTransformer.getTransformer().getClass().getName();
            throw new IllegalStateException(String.format("Failed to deserialize script metadata extracted using %s for %s", transformerName, scriptSource.getDisplayName()), e);
        } finally {
            try {
                decoder.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to close script metadata file decoder backed by " + metadataFile.getAbsolutePath(), e);
            }
        }
    }

    private static class ImperativeStatementDetector extends CompilationUnit.SourceUnitOperation {
        private final List<String> scriptBlockNames;

        private boolean hasImperativeStatements;

        private ImperativeStatementDetector(String classpathClosureName) {
            scriptBlockNames = ImmutableList.of(classpathClosureName, PluginsAndBuildscriptTransformer.PLUGINS);
        }

        @Override
        public void call(SourceUnit source) throws CompilationFailedException {
            hasImperativeStatements = hasImperativeStatements(source);
        }

        private boolean hasImperativeStatements(SourceUnit source) {
            if (source.getAST().getMethods().isEmpty()) {
                boolean hasImperativeStatements = false;
                List<Statement> statements = source.getAST().getStatementBlock().getStatements();
                if (statements.size() == 1) {
                    return !AstUtils.isReturnNullStatement(statements.get(0));
                }
                for (int i = 0; i < statements.size() && !hasImperativeStatements; i++) {
                    hasImperativeStatements = AstUtils.detectScriptBlock(statements.get(i), scriptBlockNames) == null;
                }
                return hasImperativeStatements;
            }
            return true;
        }
    }

    private static class PackageStatementDetector extends CompilationUnit.SourceUnitOperation {
        private boolean hasPackageStatement;

        @Override
        public void call(SourceUnit source) throws CompilationFailedException {
            hasPackageStatement = source.getAST().getPackageName() != null;
        }
    }

    private static class EmptyScriptDetector extends CompilationUnit.SourceUnitOperation {
        private boolean emptyScript;

        @Override
        public void call(SourceUnit source) throws CompilationFailedException {
            emptyScript = isEmpty(source);
        }

        private boolean isEmpty(SourceUnit source) {
            if (!source.getAST().getMethods().isEmpty()) {
                return false;
            }
            List<Statement> statements = source.getAST().getStatementBlock().getStatements();
            if (statements.size() > 1) {
                return false;
            }
            if (statements.isEmpty()) {
                return true;
            }

            return AstUtils.isReturnNullStatement(statements.get(0));
        }

        public boolean isEmptyScript() {
            return emptyScript;
        }
    }

    private class CustomCompilationUnit extends CompilationUnit {

        private final ScriptSource source;

        public CustomCompilationUnit(CompilerConfiguration compilerConfiguration, CodeSource codeSource, final Action<? super ClassNode> customVerifier, ScriptSource source, GroovyClassLoader groovyClassLoader) {
            super(compilerConfiguration, codeSource, groovyClassLoader);
            this.source = source;
            this.verifier = new Verifier(){
                public void visitClass(ClassNode node) {
                    customVerifier.execute(node);
                    super.visitClass(node);
                }

            };
        }

        // This creepy bit of code is here to put the full source path of the script into the debug info for
        // the class.  This makes it possible for a debugger to find the source file for the class.  By default
        // Groovy will only put the filename into the class, but that does not help a debugger for Gradle
        // because it does not know where Gradle scripts might live.
        @Override
        protected groovyjarjarasm.asm.ClassVisitor createClassVisitor() {
            return new ClassWriter(ClassWriter.COMPUTE_MAXS) {
                @Override
                public byte[] toByteArray() {
                    // ignore the sourcePath that is given by Groovy (this is only the filename) and instead
                    // insert the full path if our script source has a source file
                    visitSource(source.getFileName(), null);
                    return super.toByteArray();
                }
            };
        }
    }
}
