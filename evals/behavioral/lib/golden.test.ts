import { describe, it, expect } from 'vitest';
import { runGoldenChecks, type GoldenSpec } from './golden.js';

const BASE_SPEC: GoldenSpec = {
  required: [{ pattern: 'agent_contract:', label: 'hasAgentContract' }],
  forbidden: ['I cannot'],
};

describe('runGoldenChecks — required patterns', () => {
  it('passes when pattern present', () => {
    const result = runGoldenChecks('agent_contract: foo', BASE_SPEC);
    expect(result.details.hasAgentContract).toBe(true);
    expect(result.passed).toBe(2);
    expect(result.total).toBe(2);
  });

  it('fails when pattern absent (sad path)', () => {
    const result = runGoldenChecks('some other text', BASE_SPEC);
    expect(result.details.hasAgentContract).toBe(false);
    expect(result.passed).toBeLessThan(result.total);
  });
});

describe('runGoldenChecks — forbidden patterns', () => {
  it('passes when forbidden string absent', () => {
    const result = runGoldenChecks('all good', BASE_SPEC);
    const matchKey = Object.keys(result.details).find(key => key.startsWith('notContains'))!;
    expect(result.details[matchKey]).toBe(true);
  });

  it('fails when forbidden string present (sad path)', () => {
    const result = runGoldenChecks('I cannot do that', BASE_SPEC);
    const matchKey = Object.keys(result.details).find(key => key.startsWith('notContains'))!;
    expect(result.details[matchKey]).toBe(false);
    expect(result.passed).toBeLessThan(result.total);
  });
});

describe('runGoldenChecks — tools.expected', () => {
  const spec: GoldenSpec = {
    required: [],
    tools: { expected: ['Read'] },
  };

  it('passes when expected tool was called', () => {
    const result = runGoldenChecks('', spec, { Read: 2 });
    expect(result.details['toolUsed_Read']).toBe(true);
    expect(result.passed).toBe(1);
  });

  it('fails when expected tool was NOT called (sad path)', () => {
    const result = runGoldenChecks('', spec, {});
    expect(result.details['toolUsed_Read']).toBe(false);
    expect(result.passed).toBe(0);
    expect(result.total).toBe(1);
  });
});

describe('runGoldenChecks — tools.forbidden', () => {
  const spec: GoldenSpec = {
    required: [],
    tools: { forbidden: ['Write', 'Edit'] },
  };

  it('passes when forbidden tools were not called', () => {
    const result = runGoldenChecks('', spec, {});
    expect(result.details['toolNotUsed_Write']).toBe(true);
    expect(result.details['toolNotUsed_Edit']).toBe(true);
    expect(result.passed).toBe(2);
  });

  it('fails when forbidden tool was called (sad path)', () => {
    const result = runGoldenChecks('', spec, { Write: 1 });
    expect(result.details['toolNotUsed_Write']).toBe(false);
    expect(result.passed).toBe(1);
    expect(result.total).toBe(2);
  });
});

describe('runGoldenChecks — defaults to empty toolCalls', () => {
  it('works without toolCalls arg', () => {
    const spec: GoldenSpec = { required: [], tools: { forbidden: ['Bash'] } };
    const result = runGoldenChecks('', spec);
    expect(result.details['toolNotUsed_Bash']).toBe(true);
  });
});

describe('runGoldenChecks — yamlBlocks', () => {
  const spec: GoldenSpec = {
    required: [],
    yamlBlocks: [{
      startPattern: 'agent_contract:',
      label: 'agentContractValid',
      requiredFields: ['type', 'inputs', 'outputs'],
    }],
  };

  it('passes when YAML block has all required fields', () => {
    const response = 'agent_contract:\n  type: feature\n  inputs:\n    - name: foo\n  outputs:\n    - bar\n';
    const result = runGoldenChecks(response, spec);
    expect(result.details['agentContractValid']).toBe(true);
    expect(result.passed).toBe(1);
  });

  it('fails when startPattern is absent (sad path)', () => {
    const result = runGoldenChecks('no yaml here', spec);
    expect(result.details['agentContractValid']).toBe(false);
    expect(result.passed).toBe(0);
  });

  it('fails when required fields are missing (sad path)', () => {
    const response = 'agent_contract:\n  type: feature\n';
    const result = runGoldenChecks(response, spec);
    expect(result.details['agentContractValid']).toBe(false);
  });

  it('fails when YAML block is malformed (sad path)', () => {
    const response = 'agent_contract:\n  type: :\n  bad: [unclosed\n';
    const result = runGoldenChecks(response, spec);
    expect(result.details['agentContractValid']).toBe(false);
  });
});
