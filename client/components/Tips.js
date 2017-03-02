import React, { PropTypes, Component } from 'react';
import { render } from 'react-dom';
import { Scrollbars } from 'react-custom-scrollbars';
import { connect } from 'react-redux';
import _ from 'lodash'
import ActionsUtils from '../actions/ActionsUtils';
import ProcessUtils from '../common/ProcessUtils';

import InlinedSvgs from '../assets/icons/InlinedSvgs'


export class Tips extends Component {

  static propTypes = {
    currentProcess: React.PropTypes.object,
    testing: React.PropTypes.bool.isRequired,
    grouping: React.PropTypes.bool.isRequired

  }

  tipText = () => {
    if (ProcessUtils.isProcessValid(this.props.currentProcess)) {
      return (<div>{this.validTip()}</div>)
    } else {
      const result =  this.props.currentProcess.validationResult
      const nodesErrors = _.flatten(Object.keys(result.invalidNodes || {}).map((key, idx) => result.invalidNodes[key].map(error => this.printError(error, key, idx))))
      const globalErrors = (result.globalErrors || []).map((error, idx) => this.printError(error, null, idx))
      const processProperties = (result.processPropertiesErrors || []).map((error, idx) => this.printError(error, 'Properties', idx))
      return globalErrors.concat(processProperties.concat(nodesErrors))
    }
  }

  validTip = () => {
    if (this.props.testing) {
      return "Testing mode enabled"
    } else if (this.props.grouping) {
      return "Grouping mode enabled"
    } else {
      return "Everything seems to be OK"
    }
  }

  printError = (error, suffix, idx) => {
    return (
      <div key={idx + suffix} title={error.description}>
      {(suffix ? suffix + ": " : '') + error.message + (error.fieldName ? `(${error.fieldName})` : "")}
    </div>
    )
  }

  constructor(props) {
    super(props);
  }


  render() {
    var tipsIcon = ProcessUtils.isProcessValid(this.props.currentProcess) ? InlinedSvgs.tipsInfo : InlinedSvgs.tipsWarning
    return (
        <div id="tipsPanel">
          <Scrollbars renderThumbVertical={props => <div {...props} className="thumbVertical"/>} hideTracksWhenNotNeeded={true}>
          <div className="icon" title="" dangerouslySetInnerHTML={{__html: tipsIcon}} />
          {this.tipText()}
          </Scrollbars>
        </div>
    );
  }
}

function mapState(state) {
  return {
    currentProcess: state.graphReducer.processToDisplay || {},
    testing: state.graphReducer.testResults != null,
    grouping: state.graphReducer.groupingState != null
  };
}

export default connect(mapState, ActionsUtils.mapDispatchWithEspActions)(Tips);