/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt
package internal
package librarymanagement

import sbt.librarymanagement._
import sbt.util.Logger
import sbt.Keys._
import lmcoursier.definitions.{
  Attributes => CAttributes,
  Classifier,
  Configuration => CConfiguration,
  Dependency => CDependency,
  // Extension => CExtension,
  Info => CInfo,
  Module,
  ModuleName,
  Organization => COrganization,
  Project => CProject,
  // Publication => CPublication,
  Type => CType
}
import lmcoursier.credentials.DirectCredentials
import lmcoursier.{ FallbackDependency, FromSbt, Inputs }
import sbt.librarymanagement.ivy.{
  FileCredentials,
  Credentials,
  DirectCredentials => IvyDirectCredentials
}
import sbt.ScopeFilter.Make._
import scala.collection.JavaConverters._

private[sbt] object CoursierInputsTasks {
  private def coursierProject0(
      projId: ModuleID,
      dependencies: Seq[ModuleID],
      excludeDeps: Seq[InclExclRule],
      configurations: Seq[sbt.librarymanagement.Configuration],
      sv: String,
      sbv: String,
      log: Logger
  ): CProject = {

    val exclusions0 = Inputs.exclusions(excludeDeps, sv, sbv, log)

    val configMap = Inputs.configExtends(configurations)

    val proj = FromSbt.project(
      projId,
      dependencies,
      configMap,
      sv,
      sbv
    )

    proj.copy(
      dependencies = proj.dependencies.map {
        case (config, dep) =>
          (config, dep.copy(exclusions = dep.exclusions ++ exclusions0))
      }
    )
  }

  private[sbt] def coursierProjectTask: Def.Initialize[sbt.Task[CProject]] =
    Def.task {
      val auOpt = apiURL.value
      val proj = coursierProject0(
        projectID.value,
        allDependencies.value,
        allExcludeDependencies.value,
        // should projectID.configurations be used instead?
        ivyConfigurations.value,
        scalaVersion.value,
        scalaBinaryVersion.value,
        streams.value.log
      )
      auOpt match {
        case Some(au) =>
          val props = proj.properties :+ ("info.apiURL" -> au.toString)
          proj.copy(properties = props)
        case _ => proj
      }
    }

  private def moduleFromIvy(id: org.apache.ivy.core.module.id.ModuleRevisionId): Module =
    Module(
      COrganization(id.getOrganisation),
      ModuleName(id.getName),
      id.getExtraAttributes.asScala.map {
        case (k0, v0) => k0.asInstanceOf[String] -> v0.asInstanceOf[String]
      }.toMap
    )

  private def dependencyFromIvy(
      desc: org.apache.ivy.core.module.descriptor.DependencyDescriptor
  ): Seq[(CConfiguration, CDependency)] = {

    val id = desc.getDependencyRevisionId
    val module = moduleFromIvy(id)
    val exclusions = desc.getAllExcludeRules.map { rule =>
      // we're ignoring rule.getConfigurations and rule.getMatcher here
      val modId = rule.getId.getModuleId
      // we're ignoring modId.getAttributes here
      (COrganization(modId.getOrganisation), ModuleName(modId.getName))
    }.toSet

    val configurations = desc.getModuleConfigurations.toVector
      .flatMap(Inputs.ivyXmlMappings)

    def dependency(conf: CConfiguration, attr: CAttributes) = CDependency(
      module,
      id.getRevision,
      conf,
      exclusions,
      attr,
      optional = false,
      desc.isTransitive
    )

    val attributes: CConfiguration => CAttributes = {

      val artifacts = desc.getAllDependencyArtifacts

      val m = artifacts.toVector.flatMap { art =>
        val attr = CAttributes(CType(art.getType), Classifier(""))
        art.getConfigurations.map(CConfiguration(_)).toVector.map { conf =>
          conf -> attr
        }
      }.toMap

      c => m.getOrElse(c, CAttributes(CType(""), Classifier("")))
    }

    configurations.map {
      case (from, to) =>
        from -> dependency(to, attributes(to))
    }
  }

  private[sbt] def coursierInterProjectDependenciesTask: Def.Initialize[sbt.Task[Seq[CProject]]] =
    Def.taskDyn {

      val state = sbt.Keys.state.value
      val projectRef = sbt.Keys.thisProjectRef.value

      val projectRefs = Project.transitiveInterDependencies(state, projectRef)

      Def.task {
        val projects = csrProject.all(ScopeFilter(inProjects(projectRefs: _*))).value
        val projectModules = projects.map(_.module).toSet

        // this includes org.scala-sbt:global-plugins referenced from meta-builds in particular
        val extraProjects = sbt.Keys.projectDescriptors.value
          .map {
            case (k, v) =>
              moduleFromIvy(k) -> v
          }
          .filter {
            case (module, _) =>
              !projectModules(module)
          }
          .toVector
          .map {
            case (module, v) =>
              val configurations = v.getConfigurations.map { c =>
                CConfiguration(c.getName) -> c.getExtends.map(CConfiguration(_)).toSeq
              }.toMap
              val deps = v.getDependencies.flatMap(dependencyFromIvy)
              CProject(
                module,
                v.getModuleRevisionId.getRevision,
                deps,
                configurations,
                Nil,
                None,
                Nil,
                CInfo("", "", Nil, Nil, None)
              )
          }

        projects ++ extraProjects
      }
    }

  private[sbt] def coursierFallbackDependenciesTask
      : Def.Initialize[sbt.Task[Seq[FallbackDependency]]] =
    Def.taskDyn {
      val state = sbt.Keys.state.value
      val projectRef = sbt.Keys.thisProjectRef.value

      val projects = Project.transitiveInterDependencies(state, projectRef)

      Def.task {
        val allDeps =
          allDependencies.all(ScopeFilter(inProjects(projectRef +: projects: _*))).value.flatten

        FromSbt.fallbackDependencies(
          allDeps,
          scalaVersion.value,
          scalaBinaryVersion.value
        )
      }
    }

  val credentialsTask = Def.task {
    val log = streams.value.log
    val creds = sbt.Keys.credentials.value
      .flatMap {
        case dc: IvyDirectCredentials => List(dc)
        case fc: FileCredentials =>
          Credentials.loadCredentials(fc.path) match {
            case Left(err) =>
              log.warn(s"$err, ignoring it")
              Nil
            case Right(dc) => List(dc)
          }
      }
      .map { c =>
        DirectCredentials()
          .withHost(c.host)
          .withUsername(c.userName)
          .withPassword(c.passwd)
          .withRealm(Some(c.realm).filter(_.nonEmpty))
          .withHttpsOnly(false)
          .withMatchHost(true)
      }
    creds ++ csrExtraCredentials.value
  }
}
