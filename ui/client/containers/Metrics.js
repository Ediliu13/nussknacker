import React from 'react'
import { connect } from 'react-redux';
import { bindActionCreators } from 'redux';
import HttpService from "../http/HttpService";

class Metrics extends React.Component {

  static propTypes = {
      settings: React.PropTypes.object.isRequired,
  }

  componentDidMount() {
    if (this.props.params.processId) {
      HttpService.fetchProcessDetails(this.props.params.processId).then(details => {
          this.setState({processingType: details.processingType})
      })
    } else {
      this.setState({processingType: ""})
    }
  }

  render() {
    if (!this.props.settings.url || !this.state) {
      return (<div/>)
    }

    const url = this.props.settings.url;
    //TODO: this is still a bit grafana specific...
    const dashboard = this.getDashboardName();
    const processName = this.props.params.processId || "All";

    const finalIframeUrl = url
      .replace("$dashboard", dashboard)
      .replace("$process", processName)
    return (
      <div className="Page">
        <iframe ref="metricsFrame" src={finalIframeUrl} width="100%" height={window.innerHeight} frameBorder="0"></iframe>
      </div>
    )
  }

  getDashboardName() {
    const processingType = this.state.processingType;
    const processingTypeToDashboard = this.props.settings.processingTypeToDashboard;
    return (processingTypeToDashboard && processingTypeToDashboard[processingType]) || this.props.settings.defaultDashboard
  }

}

Metrics.basePath = "/metrics"
Metrics.path = Metrics.basePath + "(/:processId)"
Metrics.header = "Metrics"

function mapState(state) {
  return {
    settings: state.settings.featuresSettings.metrics || {}
  };
}

function mapDispatch() {
  return {
    actions: {}
  };
}

export default connect(mapState, mapDispatch)(Metrics);