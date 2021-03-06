package pl.touk.nussknacker.ui.process.uiconfig.defaults

import pl.touk.nussknacker.engine.api.definition.Parameter
import pl.touk.nussknacker.engine.api.process.ParameterConfig
import pl.touk.nussknacker.engine.definition.defaults.{NodeDefinition, ParameterDefaultValueExtractorStrategy}

class ConfigParameterDefaultValueExtractor(config: ParamDefaultValueConfig) extends ParameterDefaultValueExtractorStrategy {
  override def evaluateParameterDefaultValue(nodeDefinition: NodeDefinition,
                                             parameter: Parameter): Option[String] = {
    config.getNodeValue(nodeDefinition.id, parameter.name)
  }

}

case class ParamDefaultValueConfig(values: Map[String, Map[String, ParameterConfig]]) {
  def getNodeValue(node: String, value: String): Option[String] =
    values.get(node).flatMap(_.get(value).flatMap(_.defaultValue))
}

