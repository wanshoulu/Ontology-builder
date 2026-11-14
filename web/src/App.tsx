import { Navigate, Route, Routes } from 'react-router-dom';
import Ontology from './pages/Ontology';
import OntologyManager from './pages/OntologyManager';
import DataBrowser from './pages/DataBrowser';

/**
 * Ontology Builder — 三大工作区
 *  /modeling   抽取与建模（AI 抽取向导 + Protégé 风格编辑器 + 推理校验 + 版本管理）
 *  /manager    规则与标准（SHACL / SKOS / SWRL 逻辑规则 / 可视化编辑）
 *  /browser    实例数据浏览（SPARQL 查询 + G6 图谱可视化）
 */
export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/modeling" replace />} />
      <Route path="/modeling" element={<Ontology />} />
      <Route path="/manager/*" element={<OntologyManager />} />
      <Route path="/browser" element={<DataBrowser />} />
      <Route path="*" element={<Navigate to="/modeling" replace />} />
    </Routes>
  );
}
