/*
 * Copyright 2018 the original author or authors.
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

package build

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.transform.ArtifactTransform
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RelativePath

import org.gradle.api.internal.artifacts.dependencies.DefaultSelfResolvingDependency
import org.gradle.api.internal.classpath.ModuleRegistry
import org.gradle.api.internal.file.FileCollectionInternal

import org.gradle.kotlin.dsl.*

import org.gradle.kotlin.dsl.codegen.ParameterNamesSupplier
import org.gradle.kotlin.dsl.codegen.parameterNamesFor

import org.gradle.kotlin.dsl.support.gradleApiMetadataFrom
import org.gradle.kotlin.dsl.support.gradleApiMetadataModuleName
import org.gradle.kotlin.dsl.support.serviceOf

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
import org.objectweb.asm.ClassWriter.COMPUTE_MAXS
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ASM6
import org.objectweb.asm.Type

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import javax.inject.Inject

import kotlin.LazyThreadSafetyMode.NONE


fun Project.gradleApiWithParameterNames(): Dependency =
    DefaultSelfResolvingDependency(gradleApiWithParameterNamesFiles() as FileCollectionInternal)


private
fun Project.gradleApiWithParameterNamesFiles(): FileCollection =
    files().from(provider {
        resolveGradleApiWithParameterNames()
    })


private
val artifactType = Attribute.of("artifactType", String::class.java)


private
const val withParameterNames = "with-parameter-names"


private
fun Project.resolveGradleApiWithParameterNames(): FileCollection =
    registerGradleApiParameterNamesTransform().run {
        gradleApiDetachedConfiguration().resolveGradleApiWithParameterNamesArtifactFiles()
    }


private
fun Project.registerGradleApiParameterNamesTransform() {
    dependencies {
        registerTransform {
            from.attribute(artifactType, "jar")
            to.attribute(artifactType, withParameterNames)
            artifactTransform(GradleApiParameterNamesTransform::class) {
                params(
                    gradle.gradleVersion,
                    project.serviceOf<ModuleRegistry>().getExternalModule(gradleApiMetadataModuleName).classpath.asFiles.single()
                )
            }
        }
    }
}


private
fun Project.gradleApiDetachedConfiguration() =
    configurations.detachedConfiguration(dependencies.gradleApi())


private
fun Configuration.resolveGradleApiWithParameterNamesArtifactFiles() =
    incoming.artifactView {
        attributes {
            attribute(artifactType, withParameterNames)
        }
    }.artifacts.artifactFiles


internal
class GradleApiParameterNamesTransform @Inject constructor(
    private val gradleVersion: String,
    private val gradleApiMetadataJar: File
) : ArtifactTransform() {

    private
    val gradleApiJarFileName =
        "gradle-api-$gradleVersion.jar"

    override fun transform(input: File): MutableList<File> =
        when (input.name) {
            gradleApiJarFileName -> mutableListOf(outputDirectory).also {
                JarFile(input).use { jar ->
                    jar.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                        if (entry.isGradleApi) jar.transformEntryIntoOutputDirectory(entry)
                        else jar.writeEntryToOutputDirectory(entry)
                    }
                }
            }
            else -> mutableListOf(input)
        }

    private
    val JarEntry.isGradleApi
        get() = name.endsWith(".class")
            && name != "package-info.class"
            && gradleApiMetadata.spec.isSatisfiedBy(RelativePath.parse(true, name))

    private
    fun JarFile.transformEntryIntoOutputDirectory(entry: JarEntry) {
        getInputStream(entry).use { input ->
            val reader = ClassReader(input)
            val writer = ClassWriter(COMPUTE_MAXS and COMPUTE_FRAMES)
            val visitor = ParameterNamesClassVisitor(writer, gradleApiMetadata.parameterNamesSupplier)
            reader.accept(visitor, 0)
            outputDirectory.resolve(entry.name)
                .also { it.parentFile.mkdirs() }
                .writeBytes(writer.toByteArray())
        }
    }

    private
    val gradleApiMetadata by lazy(NONE) {
        gradleApiMetadataFrom(gradleApiMetadataJar)
    }

    private
    fun JarFile.writeEntryToOutputDirectory(entry: JarEntry) =
        getInputStream(entry).use { input ->
            outputDirectory.resolve(entry.name)
                .also { it.parentFile.mkdirs() }
                .outputStream().use { output ->
                    input.copyTo(output)
                }
        }
}


private
class ParameterNamesClassVisitor(
    delegate: ClassVisitor,
    private val parameterNamesSupplier: ParameterNamesSupplier
) : ClassVisitor(ASM6, delegate) {

    private
    lateinit var typeName: String

    override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
        typeName = name
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitMethod(access: Int, name: String, desc: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
        return super.visitMethod(access, name, desc, signature, exceptions).apply {
            parameterNamesSupplier.parameterNamesForBinaryNames(typeName, name, desc)?.forEach { parameterName ->
                visitParameter(parameterName, 0)
            }
        }
    }

    private
    fun ParameterNamesSupplier.parameterNamesForBinaryNames(typeName: String, methodName: String, methodDescriptor: String) =
        parameterNamesFor(
            typeBinaryNameFor(typeName),
            methodName,
            parameterTypesBinaryNamesFor(methodDescriptor)
        )

    private
    fun typeBinaryNameFor(internalName: String): String =
        Type.getObjectType(internalName).className

    private
    fun parameterTypesBinaryNamesFor(methodDescriptor: String) =
        Type.getArgumentTypes(methodDescriptor).map { it.className }
}
