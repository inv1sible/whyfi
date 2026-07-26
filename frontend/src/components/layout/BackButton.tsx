import { useLocation, useNavigate } from "react-router-dom";

// Hidden on the home/dashboard page — there's nowhere meaningful within
// the app to go back to from there.
export function BackButton() {
  const location = useLocation();
  const navigate = useNavigate();

  if (location.pathname === "/") return null;

  return (
    <button className="back-button" onClick={() => navigate(-1)}>
      ← Back
    </button>
  );
}
